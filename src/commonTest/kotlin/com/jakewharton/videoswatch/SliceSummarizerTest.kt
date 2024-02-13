package com.jakewharton.videoswatch

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test

class SliceSummarizerTest {
	@Test fun empty() {
		val summarizer = SliceSummarizer(framePixels = 10)
		assertThat(summarizer.summarize()).isEmpty()
	}

	@Test fun gapsAreKept() {
		val summarizer = SliceSummarizer(framePixels = 10)
		summarizer += FrameSummary(slice = 0, red = 40L, green = 160L, blue = 640L)
		summarizer += FrameSummary(slice = 2, red = 40L, green = 160L, blue = 640L)
		assertThat(summarizer.summarize()).containsExactly(
			RgbColor(r = 2.toUByte(), g = 4.toUByte(), b = 8.toUByte()),
			RgbColor(r = 0.toUByte(), g = 0.toUByte(), b = 0.toUByte()),
			RgbColor(r = 2.toUByte(), g = 4.toUByte(), b = 8.toUByte()),
		)
	}

	@Test fun expandsPastEstimate() {
		val summarizer = SliceSummarizer(framePixels = 10, sliceEstimate = 10)
		repeat(11) { slice ->
			summarizer += FrameSummary(
				slice = slice,
				red = 2 * 2 * 10L,
				green = 4 * 4 * 10L,
				blue = 8 * 8 * 10L,
			)
		}
		val expected = MutableList(11) {
			RgbColor(r = 2.toUByte(), g = 4.toUByte(), b = 8.toUByte())
		}
		assertThat(summarizer.summarize()).isEqualTo(expected)
	}

	@Test fun summarizeDividesAndRoots() {
		val summarizer = SliceSummarizer(framePixels = 10)
		for (i in 10..100 step 10) {
			summarizer += FrameSummary(
				slice = 0,
				red = i * i * 10L,
				green = i * i * 10L,
				blue = i * i * 10L,
			)
		}
		val colors = summarizer.summarize()
		assertThat(colors).containsExactly(
			RgbColor(r = 62.toUByte(), g = 62.toUByte(), b = 62.toUByte()),
		)
	}
}
