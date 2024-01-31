package com.jakewharton.videoswatch

import java.io.FileNotFoundException
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.notExists

@Suppress("UnsafeDynamicallyLoadedCode") // Only loading from our own JAR contents.
internal fun loadFfmpeg() {
	val osName = System.getProperty("os.name").lowercase(Locale.US)

	if (osName.contains("mac")) {
		loadLibrary("libavcodec.dylib")
		loadLibrary("libavformat.dylib")
		loadLibrary("libavutil.dylib")
		loadLibrary("libswscale.dylib")
	} else {
		throw IllegalStateException("Unsupported OS: $osName")
	}
}

private fun loadLibrary(libraryRelativePath: String) {
	val overridePath = System.getProperty("video-swatch.library.path")
	val nativeDir = if (overridePath != null) {
		Path.of(overridePath)
	} else {
		Path.of(SwatchCommand::class.java.protectionDomain.codeSource.location.path)
			.parent // Libs folder.
			.parent // App folder.
			.resolve("native")
	}

	val libraryPath = nativeDir.resolve(libraryRelativePath)
	if (libraryPath.notExists()) {
		throw FileNotFoundException("Unable to locate $libraryPath")
	}
	System.load(libraryPath.toAbsolutePath().toString())
	println("Loaded $libraryRelativePath")
}
