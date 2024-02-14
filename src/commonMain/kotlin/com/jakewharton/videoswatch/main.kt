@file:Suppress("DEPRECATION")

package com.jakewharton.videoswatch

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.jakewharton.videoswatch.ffmpeg.AVCodec
import com.jakewharton.videoswatch.ffmpeg.AVERROR_EOF
import com.jakewharton.videoswatch.ffmpeg.AVFormatContext
import com.jakewharton.videoswatch.ffmpeg.AVMEDIA_TYPE_VIDEO
import com.jakewharton.videoswatch.ffmpeg.AVPacket
import com.jakewharton.videoswatch.ffmpeg.AV_PIX_FMT_RGB0
import com.jakewharton.videoswatch.ffmpeg.SWS_BILINEAR
import com.jakewharton.videoswatch.ffmpeg.av_dump_format
import com.jakewharton.videoswatch.ffmpeg.av_find_best_stream
import com.jakewharton.videoswatch.ffmpeg.av_frame_alloc
import com.jakewharton.videoswatch.ffmpeg.av_free
import com.jakewharton.videoswatch.ffmpeg.av_image_fill_arrays
import com.jakewharton.videoswatch.ffmpeg.av_image_get_buffer_size
import com.jakewharton.videoswatch.ffmpeg.av_packet_unref
import com.jakewharton.videoswatch.ffmpeg.av_read_frame
import com.jakewharton.videoswatch.ffmpeg.avcodec_alloc_context3
import com.jakewharton.videoswatch.ffmpeg.avcodec_free_context2
import com.jakewharton.videoswatch.ffmpeg.avcodec_open2
import com.jakewharton.videoswatch.ffmpeg.avcodec_parameters_to_context
import com.jakewharton.videoswatch.ffmpeg.avcodec_receive_frame
import com.jakewharton.videoswatch.ffmpeg.avcodec_send_packet
import com.jakewharton.videoswatch.ffmpeg.avformat_close_input2
import com.jakewharton.videoswatch.ffmpeg.avformat_find_stream_info
import com.jakewharton.videoswatch.ffmpeg.avformat_open_input
import com.jakewharton.videoswatch.ffmpeg.sws_getContext
import com.jakewharton.videoswatch.ffmpeg.sws_scale
import kotlin.system.getTimeNanos
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.measureTime
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.EAGAIN
import platform.posix.uint8_tVar

fun main(vararg args: String) {
	SwatchCommand(
		clock = Clock.System,
		timeZone = TimeZone.currentSystemDefault(),
		outputFs = FileSystem.SYSTEM,
	).main(args)
}

private class SwatchCommand(
	private val clock: Clock,
	private val timeZone: TimeZone,
	private val outputFs: FileSystem,
) : CliktCommand(name = "video-swatch") {
	private val fileName by argument(name = "VIDEO")
	private val outputPng by option(metavar = "FILE").convert { it.toPath() }
	private val outputTxt by option(metavar = "FILE").convert { it.toPath() }
	private val debug by option().flag()

	private fun debugLog(message: () -> String) {
		if (debug) {
			val time = clock.now().toLocalDateTime(timeZone).time.toString()
			val indented = message()
				.replace("\n", "\n" + " ".repeat(time.length + 3))
			println("[$time] $indented")
		}
	}

	override fun run(): Unit = closeFinallyScope {
		memScoped {
			val formatContextVar = allocPointerTo<AVFormatContext>()
			avformat_open_input(formatContextVar.ptr, fileName, null, null).checkReturn {
				"Unable to open $fileName"
			}
			val formatContext = formatContextVar.value!!
			closer += { avformat_close_input2(formatContext) }
			debugLog { "Opened input" }

			avformat_find_stream_info(formatContext, null).checkReturn {
				"Unable to get stream info for $fileName"
			}
			debugLog { "Got stream info" }

			if (debug) {
				av_dump_format(formatContext, 0, fileName, 0)
			}

			val codecVar = allocPointerTo<AVCodec>()
			val videoIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_VIDEO, -1, -1, codecVar.ptr, 0).checkReturn {
				"Didn't find a video stream"
			}
			val codec = codecVar.value!!
			debugLog { "Found video stream (index: $videoIndex)" }

			val codecParameters = formatContext.pointed.streams!![videoIndex]!!.pointed.codecpar!!
			val codecContext = avcodec_alloc_context3(codec)
				.checkAlloc("codecContext")
				.scopedUseWithClose(::avcodec_free_context2)

			avcodec_parameters_to_context(codecContext, codecParameters).checkReturn {
				"Cannot copy parameters to context"
			}
			debugLog { "Parameters copied to context" }

			avcodec_open2(codecContext, codec, null).checkReturn {
				"Cannot open codec"
			}
			debugLog { "Opened codec" }

			val frameWidth = codecContext.pointed.width
			val frameHeight = codecContext.pointed.height
			val encodedFormat = codecContext.pointed.pix_fmt
			val decodedFormat = AV_PIX_FMT_RGB0

			val swsContext = sws_getContext(
				frameWidth,
				frameHeight,
				encodedFormat,
				frameWidth,
				frameHeight,
				decodedFormat,
				SWS_BILINEAR,
				null,
				null,
				null,
			).checkAlloc("swsContext")

			val frameRate = codecParameters.pointed.framerate.run { num.toFloat() / den }
			val framePixelCount = frameWidth * frameHeight

			val bufferSize = av_image_get_buffer_size(decodedFormat, frameWidth, frameHeight, 1)
			check(bufferSize == framePixelCount * 4)
			val decodedBuffer = allocArray<uint8_tVar>(bufferSize)
				.checkAlloc("decodedBuffer")
			val decodedFrame = av_frame_alloc()
				.checkAlloc("decodedFrame")
				.scopedUseWithClose(::av_free)
				.pointed
			av_image_fill_arrays(decodedFrame.data, decodedFrame.linesize, decodedBuffer, decodedFormat, frameWidth, frameHeight, 1)

			val encodedFrame = av_frame_alloc()
				.checkAlloc("encodedFrame")
				.scopedUseWithClose(::av_free)
				.pointed

			val sliceSummarizer = SliceSummarizer(framePixelCount)

			var sliceRemainingFrames = frameRate
			var sliceIndex = 0

			var frameIndex = 0
			var lastFrameIndex = frameIndex
			val firstFrameTime = getTimeNanos()
			var lastFrameTime = firstFrameTime

			val avPacket = alloc<AVPacket>()
			while (av_read_frame(formatContext, avPacket.ptr) >= 0) {
				if (avPacket.stream_index == videoIndex) {
					while (true) {
						when (val sendPacketResult = avcodec_send_packet(codecContext, avPacket.ptr)) {
							// Packet was accepted by decoder. Break to outer loop to read another.
							0 -> break
							// Decoder buffers are full. Continue to inner drain loop before retrying this one.
							-EAGAIN -> {}

							else -> throw IllegalStateException("Error sending packet to decoder: $sendPacketResult")
						}

						while (true) {
							val receiveFrameResult: Int
							val receiveFrameTook = measureTime {
								receiveFrameResult = avcodec_receive_frame(codecContext, encodedFrame.ptr)
							}
							when (receiveFrameResult) {
								0 -> {
									val conversionTook = measureTime {
										sws_scale(
											swsContext,
											encodedFrame.data,
											encodedFrame.linesize,
											0,
											frameHeight,
											decodedFrame.data,
											decodedFrame.linesize,
										)
									}

									val scanPixelsTook = measureTime {
										val data = decodedFrame.data[0]!!

										var frameRedSum = 0L
										var frameGreenSum = 0L
										var frameBlueSum = 0L
										for (i in 0 until bufferSize step 4) {
											val red = data[i].toInt()
											frameRedSum += red * red
											val green = data[i + 1].toInt()
											frameGreenSum += green * green
											val blue = data[i + 2].toInt()
											frameBlueSum += blue * blue
										}

										sliceSummarizer += FrameSummary(
											slice = sliceIndex,
											red = frameRedSum,
											green = frameGreenSum,
											blue = frameBlueSum,
										)
									}

									val timeNanos = getTimeNanos()
									val timeDelta = timeNanos - lastFrameTime
									if (timeDelta > 1_000_000_000L) {
										lastFrameTime = timeNanos
										val frames = frameIndex - lastFrameIndex
										val avg = frameIndex / ((timeNanos - firstFrameTime) / 1_000_000_000)
										println("${frameIndex + 1} frames processed, $frames fps ($avg average)")
										lastFrameIndex = frameIndex
									}

									debugLog {
										"""
										|FRAME $frameIndex
										|  slice index: $sliceIndex
										|  slice frames remaining: $sliceRemainingFrames
										|  receiveFrame: $receiveFrameTook
										|  conversion: $conversionTook
										|  scanPixels: $scanPixelsTook
										""".trimMargin()
									}

									sliceRemainingFrames--
									if (sliceRemainingFrames < 0) {
										sliceIndex++
										// Add instead of assigning to retain fractional remainder.
										sliceRemainingFrames += frameRate
									}

									frameIndex++
								}

								AVERROR_EOF, -EAGAIN -> break
								else -> throw IllegalStateException("Error receiving frame $receiveFrameResult")
							}
						}
					}
				}
				av_packet_unref(avPacket.ptr)
			}

			val totalNanos = getTimeNanos() - firstFrameTime
			val totalDuration = totalNanos.nanoseconds
			val totalFps = frameIndex / (totalNanos / 1_000_000_000)
			println()
			println("${frameIndex + 1} frames, $totalFps fps, $totalDuration")

			val colors = sliceSummarizer.summarize()

			outputPng?.let { outputPng ->
				val png = renderPng(colors)
				outputFs.write(outputPng) {
					write(png)
				}
			}

			outputTxt?.let { outputTxt ->
				val txt = renderTxt(colors)
				outputFs.write(outputTxt) {
					writeUtf8(txt)
				}
			}
		}
	}
}
