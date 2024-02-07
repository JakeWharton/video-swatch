package com.jakewharton.videoswatch

internal inline fun Int.checkReturn(message: () -> String) = apply {
	check(this >= 0) { message() + " ($this)" }
}

internal fun <T : Any> T?.checkAlloc(name: String? = null) = checkNotNull(this) {
	buildString {
		append("Unable to allocate")
		if (name != null) {
			append(": ")
			append(name)
		}
	}
}
