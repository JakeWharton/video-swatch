package com.jakewharton.videoswatch

data class RgbColor(
	val r: UByte,
	val g: UByte,
	val b: UByte,
) {
	override fun toString(): String {
		return "#" +
			r.toString(16).padStart(2, '0') +
			g.toString(16).padStart(2, '0') +
			b.toString(16).padStart(2, '0')
	}
}
