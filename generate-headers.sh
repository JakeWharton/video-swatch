#/usr/bin/env bash

set -e

rm -rf src/main/headers src/main/native
mkdir -p src/main/headers src/main/native

jextract \
	-t libffmpeg \
	-I /Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk/usr/include \
	-I $(brew --prefix ffmpeg)/include \
	-l avcodec \
	-l avformat \
	-l avutil \
	-l swscale \
	--source \
	--header-class-name Libffmpeg \
	--output src/main/headers \
	src/main/libffmpeg.h

cp $(brew --prefix ffmpeg)/lib/libavcodec.dylib src/main/native/
cp $(brew --prefix ffmpeg)/lib/libavformat.dylib src/main/native/
cp $(brew --prefix ffmpeg)/lib/libavutil.dylib src/main/native/
cp $(brew --prefix ffmpeg)/lib/libswscale.dylib src/main/native/
