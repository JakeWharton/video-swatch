package com.jakewharton.videoswatch

import com.jakewharton.videoswatch.png.PNG_FORMAT_RGB
import com.jakewharton.videoswatch.png.PNG_IMAGE_VERSION
import com.jakewharton.videoswatch.png.png_alloc_size_tVar
import com.jakewharton.videoswatch.png.png_byteVar
import com.jakewharton.videoswatch.png.png_image
import com.jakewharton.videoswatch.png.png_image_write_to_memory
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import okio.ByteString
import okio.ByteString.Companion.toByteString
import platform.posix.uint8_tVar

internal fun renderTxt(colors: List<RgbColor>): String = buildString(colors.size * 8) {
	for (color in colors) {
		append(color)
		append('\n')
	}
}

internal fun renderPng(colors: List<RgbColor>): ByteString = memScoped {
	require(colors.isNotEmpty()) { "Colors must be non-empty" }

	val width = colors.size
	val stride = width * 3
	val height = (width * 9 / 16).coerceAtLeast(1)

	val png = alloc<png_image>()
	png.width = width.toUInt()
	png.height = height.toUInt()
	png.format = PNG_FORMAT_RGB
	png.version = PNG_IMAGE_VERSION.convert()

	val buffer = allocArray<png_byteVar>(stride * height)
	for (y in 0 until height) {
		for (x in 0 until width) {
			val color = colors[x]
			val offset = y * stride + x * 3
			buffer[offset] = color.r
			buffer[offset + 1] = color.g
			buffer[offset + 2] = color.b
		}
	}

	val pngSizeVar = alloc<png_alloc_size_tVar>().checkAlloc("pngSizeVar")
	val sizeWriteResult = png_image_write_to_memory(
		image = png.ptr,
		memory = null,
		memory_bytes = pngSizeVar.ptr,
		convert_to_8_bit = 0,
		buffer = buffer,
		row_stride = stride,
		colormap = null,
	)
	check(sizeWriteResult != 0) {
		"Size write failed: ${png.message.toKString()}"
	}

	val pngSize = pngSizeVar.value.toInt()
	check(pngSizeVar.value == pngSize.toULong()) {
		"PNG size ${pngSizeVar.value} too large"
	}

	val pngBytes = allocArray<uint8_tVar>(pngSize)
	val bufferWriteResult = png_image_write_to_memory(
		image = png.ptr,
		memory = pngBytes,
		memory_bytes = pngSizeVar.ptr,
		convert_to_8_bit = 0,
		buffer = buffer,
		row_stride = stride,
		colormap = null,
	)
	check(bufferWriteResult != 0) {
		"Buffer write failed: ${png.message.toKString()}"
	}

	// https://github.com/square/okio/issues/1431
	return pngBytes.readBytes(pngSize).toByteString()
}
