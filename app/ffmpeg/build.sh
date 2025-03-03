#!/bin/bash

if [ -z ${ANDROID_NDK_HOME+x} ]; then
	echo "Error: \$ANDROID_NDK_HOME is not defined." >&2
	exit 1
elif [ $# -lt 1 ]; then
	echo "Usage: $0 <FFmpeg source directory> [<ABI>]" >&2
	exit 1
else
	FFMPEG_DIR=$1
	compile_for_arch() {
		echo "Compiling for $1..."
		case $1 in
			"x86_64")
				CFN="x86_64-linux-android21-clang"
				ARCH="x86_64"
				;;
			"x86")
				CFN="i686-linux-android21-clang"
				ARCH="i686"
				EXTRA_FLAGS="--disable-asm"
				;;
			"arm64-v8a")
				CFN="aarch64-linux-android21-clang"
				ARCH="aarch64"
				;;
			"armeabi-v7a")
				CFN="armv7a-linux-androideabi19-clang"
				ARCH="arm"
				;;
		esac
		(cd $FFMPEG_DIR && make clean;
		./configure \
			--cc="$CFN" \
			--cxx="$CFN++" \
			--arch="$ARCH" \
			$EXTRA_FLAGS \
			--target-os=android \
			--enable-cross-compile \
			--enable-version3 \
			--disable-programs \
			--disable-static \
			--enable-shared \
			--disable-bsfs \
			--disable-parsers \
			--disable-demuxers \
			--disable-muxers \
			--enable-muxer="mp4" \
			--disable-decoders \
			--disable-encoders \
			--enable-encoder="aac" \
			--disable-avdevice \
			--disable-swresample \
			--disable-swscale \
			--disable-postproc \
			--disable-avfilter \
			--disable-network \
			--disable-doc \
			--disable-htmlpages \
			--disable-manpages \
			--disable-podpages \
			--disable-txtpages \
			--disable-sndio \
			--disable-schannel \
			--disable-securetransport \
			--disable-xlib \
			--disable-zlib \
			--disable-cuvid \
			--disable-nvenc \
			--disable-vdpau \
			--disable-videotoolbox \
			--disable-audiotoolbox \
			--disable-appkit \
			--disable-alsa \
			--disable-debug \
			>/dev/null &&
		make -j $(nproc --all) >/dev/null) &&
		mkdir -p build/$1/libavformat build/$1/libavcodec build/$1/libavutil &&
		cp $FFMPEG_DIR/libavformat/*.h $FFMPEG_DIR/libavformat/libavformat.so build/$1/libavformat &&
		cp $FFMPEG_DIR/libavcodec/*.h $FFMPEG_DIR/libavcodec/libavcodec.so build/$1/libavcodec &&
		cp $FFMPEG_DIR/libavutil/*.h $FFMPEG_DIR/libavutil/libavutil.so build/$1/libavutil ||
		exit 1
	}

	export PATH=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
	if [ $# -eq 2 ]; then
		compile_for_arch $2
	else
		declare -a ABIs=("x86_64" "x86" "arm64-v8a" "armeabi-v7a")
		for abi in ${ABIs[@]}; do
			compile_for_arch $abi
		done
	fi
fi
