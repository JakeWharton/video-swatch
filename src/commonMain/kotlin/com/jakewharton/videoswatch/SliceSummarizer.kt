package com.jakewharton.videoswatch

import kotlin.math.sqrt

data class FrameSummary(
	/** The sum of the square of each red pixel in a frame. */
	val red: Long,
	/** The sum of the square of each green pixel in a frame. */
	val green: Long,
	/** The sum of the square of each blue pixel in a frame. */
	val blue: Long,
)

class SliceSummarizer(
	/** The number of pixels in each frame. */
	private val framePixels: Int,
	/** An estimate for the number of slices. */
	sliceEstimate: Int = 1000,
) {
	private var length = sliceEstimate.coerceAtLeast(10)
	private var reds = DoubleArray(length)
	private var greens = DoubleArray(length)
	private var blues = DoubleArray(length)
	private var frameCounts = IntArray(length)
	private var sliceCount = 0

	private fun doubleStorage() {
		val newLength = length * 2
		reds = reds.copyOf(newLength)
		greens = greens.copyOf(newLength)
		blues = blues.copyOf(newLength)
		frameCounts = frameCounts.copyOf(newLength)
		length = newLength
	}

	fun addToSlice(slice: Int, frameSummary: FrameSummary) {
		if (slice >= length) {
			doubleStorage()
		}
		sliceCount = maxOf(sliceCount, slice + 1)

		reds[slice] += frameSummary.red.toDouble()
		greens[slice] += frameSummary.green.toDouble()
		blues[slice] += frameSummary.blue.toDouble()
		frameCounts[slice]++
	}

	fun summarize(): List<RgbColor> {
		return MutableList(sliceCount) { slice ->
			val frameCount = frameCounts[slice]
			val slicePixels = frameCount * framePixels
			RgbColor(
				r = sqrt(reds[slice] / slicePixels).toUInt().toUByte(),
				g = sqrt(greens[slice] / slicePixels).toUInt().toUByte(),
				b = sqrt(blues[slice] / slicePixels).toUInt().toUByte(),
			)
		}
	}
}
