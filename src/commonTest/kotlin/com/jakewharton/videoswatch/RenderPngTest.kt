package com.jakewharton.videoswatch

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import okio.ByteString
import okio.ByteString.Companion.decodeHex

class RenderPngTest {
	@Test fun empty() {
		assertFailure { renderPng(emptyList()) }
			.isInstanceOf<IllegalArgumentException>()
			.hasMessage("Colors must be non-empty")
	}

	@Test fun one() {
		assertThat(
			renderPng(
				listOf(
					RgbColor(r = 16.toUByte(), 32.toUByte(), 64.toUByte()),
				),
			),
		).isEqualTo(
			"""
			8950 4e47 0d0a 1a0a 0000 000d 4948 4452
			0000 0001 0000 0001 0802 0000 0090 7753
			de00 0000 0173 5247 4200 aece 1ce9 0000
			000c 4944 4154 0899 6310 5070 0000 00b4
			0071 24e1 96a9 0000 0000 4945 4e44 ae42
			6082
			""".decodeHexWithWhitespace(),
		)
	}

	@Test fun many() {
		assertThat(
			renderPng(
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
			8950 4e47 0d0a 1a0a 0000 000d 4948 4452
			0000 0005 0000 0002 0802 0000 001f 0881
			0a00 0000 0173 5247 4200 aece 1ce9 0000
			001c 4944 4154 0899 6316 5070 90fc 5875
			2af4 c97b 65f3 7b37 1733 31a0 0200 ac8d
			07a0 30ce ec9c 0000 0000 4945 4e44 ae42
			6082
			""".decodeHexWithWhitespace(),
		)
	}

	private fun String.decodeHexWithWhitespace(): ByteString {
		return replace(Regex("\\s"), "").decodeHex()
	}
}
