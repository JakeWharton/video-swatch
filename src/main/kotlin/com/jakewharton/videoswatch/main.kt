@file:JvmName("Main")

package com.jakewharton.videoswatch

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment.NULL
import libffmpeg.AVCodecContext
import libffmpeg.AVFormatContext
import libffmpeg.AVStream
import libffmpeg.Libffmpeg.AVMEDIA_TYPE_VIDEO
import libffmpeg.Libffmpeg.AV_PIX_FMT_RGB24
import libffmpeg.Libffmpeg.C_CHAR
import libffmpeg.Libffmpeg.C_POINTER
import libffmpeg.Libffmpeg.av_find_best_stream
import libffmpeg.Libffmpeg.av_frame_alloc
import libffmpeg.Libffmpeg.av_free
import libffmpeg.Libffmpeg.av_image_get_buffer_size
import libffmpeg.Libffmpeg.av_malloc
import libffmpeg.Libffmpeg.avcodec_alloc_context3
import libffmpeg.Libffmpeg.avcodec_close
import libffmpeg.Libffmpeg.avcodec_open2
import libffmpeg.Libffmpeg.avcodec_parameters_to_context
import libffmpeg.Libffmpeg.avformat_close_input
import libffmpeg.Libffmpeg.avformat_find_stream_info
import libffmpeg.Libffmpeg.avformat_open_input


fun main(vararg args: String) {
	SwatchCommand().main(args)
}

internal class SwatchCommand : CliktCommand(name = "video-swatch") {
	private val fileName by argument(name = "VIDEO")

	override fun run() = closeFinallyScope {
		val arena = Arena.ofConfined().useInScope()

		val formatContextPtr = arena.allocate(C_POINTER)
		val fileNameCstr = arena.allocateUtf8String(fileName)
		avformat_open_input(formatContextPtr, fileNameCstr, NULL, NULL).checkReturn {
			"Unable to open $fileName"
		}
		closer += { avformat_close_input(formatContextPtr) }
		val formatContext = formatContextPtr.get(C_POINTER, 0)
		println("Opened input")

		avformat_find_stream_info(formatContext, NULL).checkReturn {
			"Unable to get stream info for $fileName"
		}
		println("Got stream info")

		// av_dump_format(formatContext, 0, fileNameCstr, 0)

		val decoderPtr = arena.allocate(C_POINTER)
		val videoStream = av_find_best_stream(formatContext, AVMEDIA_TYPE_VIDEO(), -1, -1, decoderPtr, 0).checkReturn {
			"Didn't find a video stream: $it"
		}
		val decoder = decoderPtr.get(C_POINTER, 0)
		println("Found video stream (index: $videoStream)")

		val streams = AVFormatContext.`streams$get`(formatContext, videoStream.toLong())
		val codecParameters = AVStream.`codecpar$get`(streams)
		val decoderContext = avcodec_alloc_context3(decoder)
		avcodec_parameters_to_context(decoderContext, codecParameters).checkReturn {
			"Cannot copy parameters to context"
		}
		println("Parameters copied to context")

		avcodec_open2(decoderContext, decoder, NULL).checkReturn {
			"Cannot open codec"
		}
		closer += { avcodec_close(decoderContext) }
		println("Opened codec")

		val pFrame = av_frame_alloc().checkAlloc("frame").useWithClose(::av_free)
		val pFrameRGB = av_frame_alloc().checkAlloc("frameRGB").useWithClose(::av_free)

		// Determine required buffer size and allocate buffer
		val width = AVCodecContext.`width$get`(codecParameters)
		val height = AVCodecContext.`height$get`(codecParameters)
		val size = av_image_get_buffer_size(AV_PIX_FMT_RGB24(), width, height, 1)
		val buffer = av_malloc(size * C_CHAR.byteSize()).checkAlloc("buffer").useWithClose(::av_free)
	}
}

private fun <T> T.checkAlloc(name: String? = null) = apply {
	if (this == NULL) {
		throw NullPointerException(buildString {
			append("Unable to allocate")
			if (name != null) {
				append(": ")
				append(name)
			}
		})
	}
}

private inline fun Int.checkReturn(message: (code: Int) -> String) = apply {
	check(this >= 0) { message(this) }
}
