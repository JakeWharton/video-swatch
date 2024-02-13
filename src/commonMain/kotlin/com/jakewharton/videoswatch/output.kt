package com.jakewharton.videoswatch

import com.jakewharton.videoswatch.png.PNG_FORMAT_RGB
import com.jakewharton.videoswatch.png.PNG_IMAGE_VERSION
import com.jakewharton.videoswatch.png.png_byteVar
import com.jakewharton.videoswatch.png.png_image
import com.jakewharton.videoswatch.png.png_image_write_to_file
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set

internal fun createTxt(colors: List<RgbColor>): String = buildString(colors.size * 8) {
	for (color in colors) {
		append(color)
		append('\n')
	}
}

internal fun writePng(colors: List<RgbColor>, file: String) = memScoped {
	val width = colors.size
	val stride = width * 3
	val height = width / 16 * 9

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

	png_image_write_to_file(
		image = png.ptr,
		file = file,
		convert_to_8bit = 0,
		buffer = buffer,
		row_stride = 0,
		colormap = null,
	)
}
