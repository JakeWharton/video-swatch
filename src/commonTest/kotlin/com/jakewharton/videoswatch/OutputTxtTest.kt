package com.jakewharton.videoswatch

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test

class OutputTxtTest {
	@Test fun empty() {
		assertThat(createTxt(emptyList())).isEmpty()
	}

	@Test fun one() {
		assertThat(
			createTxt(
				listOf(
					RgbColor(r = 16.toUByte(), 32.toUByte(), 64.toUByte()),
				),
			),
		).isEqualTo(
			"""
			|#102040
			|
			""".trimMargin(),
		)
	}

	@Test fun many() {
		assertThat(
			createTxt(
				listOf(
					RgbColor(r = 16.toUByte(), 32.toUByte(), 64.toUByte()),
					RgbColor(r = 33.toUByte(), 1.toUByte(), 154.toUByte()),
					RgbColor(r = 218.toUByte(), 85.toUByte(), 49.toUByte()),
					RgbColor(r = 92.toUByte(), 77.toUByte(), 79.toUByte()),
					RgbColor(r = 12.toUByte(), 255.toUByte(), 202.toUByte()),
				),
			),
		).isEqualTo(
			"""
			|#102040
			|#21019a
			|#da5531
			|#5c4d4f
			|#0cffca
			|
			""".trimMargin(),
		)
	}
}
