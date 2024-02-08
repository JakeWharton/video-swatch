package com.jakewharton.videoswatch

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.jakewharton.videoswatch.ffmpeg.AVCodec
import com.jakewharton.videoswatch.ffmpeg.AVERROR_EOF
import com.jakewharton.videoswatch.ffmpeg.AVFormatContext
import com.jakewharton.videoswatch.ffmpeg.AVMEDIA_TYPE_VIDEO
import com.jakewharton.videoswatch.ffmpeg.AVPacket
import com.jakewharton.videoswatch.ffmpeg.AV_PIX_FMT_RGB24
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
import kotlin.math.pow
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
import platform.posix.EAGAIN
import platform.posix.sqrt
import platform.posix.uint8_tVar

fun main(vararg args: String) {
	SwatchCommand(
		clock = Clock.System,
		timeZone = TimeZone.currentSystemDefault(),
	).main(args)
}

private class SwatchCommand(
	private val clock: Clock,
	private val timeZone: TimeZone,
) : CliktCommand(name = "video-swatch") {
	private val fileName by argument(name = "VIDEO")
	private val outputPng by option(metavar = "FILE")
	private val outputTxt by option(metavar = "FILE")
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

			val decoderVar = allocPointerTo<AVCodec>()
			val videoIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_VIDEO, -1, -1, decoderVar.ptr, 0).checkReturn {
				"Didn't find a video stream"
			}
			val decoder = decoderVar.value!!
			debugLog { "Found video stream (index: $videoIndex)" }

			val codecParameters = formatContext.pointed.streams!![videoIndex]!!.pointed.codecpar!!
			val decoderContext = avcodec_alloc_context3(decoder)
				.checkAlloc("decoderContext")
				.scopedUseWithClose(::avcodec_free_context2)

			avcodec_parameters_to_context(decoderContext, codecParameters).checkReturn {
				"Cannot copy parameters to context"
			}
			debugLog { "Parameters copied to context" }

			avcodec_open2(decoderContext, decoder, null).checkReturn {
				"Cannot open codec"
			}
			debugLog { "Opened codec" }

			val frame = av_frame_alloc().checkAlloc("frame").scopedUseWithClose(::av_free)
			val frameRgb = av_frame_alloc().checkAlloc("frameRgb").scopedUseWithClose(::av_free)

			val width = codecParameters.pointed.width
			val height = codecParameters.pointed.height
			val frameRate = codecParameters.pointed.framerate.num.toFloat() / codecParameters.pointed.framerate.den
			val framePixelCount = width * height
			val bufferSize = av_image_get_buffer_size(AV_PIX_FMT_RGB24, width, height, 1)
			check(bufferSize == framePixelCount * 3)
			val buffer = allocArray<uint8_tVar>(bufferSize).checkAlloc("buffer")

			av_image_fill_arrays(frameRgb.pointed.data, frameRgb.pointed.linesize, buffer, AV_PIX_FMT_RGB24, width, height, 1)

			val pixelFormat = decoderContext.pointed.pix_fmt
			val swsContext = sws_getContext(
				width,
				height,
				pixelFormat,
				width,
				height,
				AV_PIX_FMT_RGB24,
				SWS_BILINEAR,
				null,
				null,
				null,
			).checkAlloc("swsContext")

			val avPacket = alloc<AVPacket>()

			var groupRemainingFrames = frameRate
			var groupFrameCount = 0
			var groupRedSum = 0.0
			var groupGreenSum = 0.0
			var groupBlueSum = 0.0

			val colors = mutableListOf<RgbColor>()
			fun checkForCompleteGroup(done: Boolean = false) {
				val isComplete = groupRemainingFrames < 0
				val isHalfway = frameRate - groupRemainingFrames > groupRemainingFrames
				if (isComplete || done && isHalfway) {
					val groupPixelCount = groupFrameCount * framePixelCount

					val redMean = sqrt(groupRedSum / groupPixelCount).toInt()
					val greenMean = sqrt(groupGreenSum / groupPixelCount).toInt()
					val blueMean = sqrt(groupBlueSum / groupPixelCount).toInt()
					val color = RgbColor(
						r = redMean.toUByte(),
						g = greenMean.toUByte(),
						b = blueMean.toUByte(),
					)

					debugLog {
						"""
						|COLOR $color
						|  resolution = $width * $height = $framePixelCount
						|  frames = $groupFrameCount
						|  pixels = frames * resolution = $groupPixelCount
						|  redSum = $groupRedSum
						|  greenSum = $groupGreenSum
						|  blueSum = $groupBlueSum
						""".trimMargin()
					}

					colors += color

					groupFrameCount = 0
					// Add instead of assigning to retain fractional remainder.
					groupRemainingFrames += frameRate

					groupRedSum = 0.0
					groupGreenSum = 0.0
					groupBlueSum = 0.0
				}
			}

			var frameIndex = 0U
			while (true) {
				val readFrameResult: Int
				val readFrameTook = measureTime {
					readFrameResult = av_read_frame(formatContext, avPacket.ptr)
				}
				if (readFrameResult < 0) break

				if (avPacket.stream_index == videoIndex) {
					val sendPacketResult: Int
					val sendPacketTook = measureTime {
						sendPacketResult = avcodec_send_packet(decoderContext, avPacket.ptr)
					}
					sendPacketResult.checkReturn {
						"Error sending packet to decoder"
					}

					while (true) {
						val receiveFrameResult: Int
						val receiveFrameTook = measureTime {
							receiveFrameResult = avcodec_receive_frame(decoderContext, frame)
						}
						when (receiveFrameResult) {
							0 -> {
								val frameValue = frame.pointed
								val frameRgbValue = frameRgb.pointed

								groupRemainingFrames--
								checkForCompleteGroup()

								val conversionTook = measureTime {
									sws_scale(
										swsContext,
										frameValue.data,
										frameValue.linesize,
										0,
										height,
										frameRgbValue.data,
										frameRgbValue.linesize,
									)
								}

								val scanPixelsTook = measureTime {
									val data = frameRgbValue.data[0]!!
									for (i in 0 until bufferSize step 3) {
										groupRedSum += data[i].toDouble().pow(2)
										groupGreenSum += data[i + 1].toDouble().pow(2)
										groupBlueSum += data[i + 2].toDouble().pow(2)
									}
								}

								frameIndex++
								groupFrameCount++
								debugLog {
									"""
									|FRAME $frameIndex
									|  group frames processed: $groupFrameCount
									|  group frames remaining: $groupRemainingFrames
									|  readFrame: $readFrameTook
									|  sendPacket: $sendPacketTook
									|  receiveFrame: $receiveFrameTook
									|  conversion: $conversionTook
									|  scanPixels: $scanPixelsTook
									""".trimMargin()
								}
							}

							AVERROR_EOF, -EAGAIN -> break
							else -> throw IllegalStateException("Error receiving frame $receiveFrameResult")
						}
					}
				}
				av_packet_unref(avPacket.ptr)
			}

			checkForCompleteGroup(done = true)

			outputPng?.let { writePng(colors, it) }
			outputTxt?.let { writeTxt(colors, it) }
		}
	}
}
