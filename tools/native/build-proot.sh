#!/bin/sh
# Builds PRoot (termux/proot, GPL-2.0-or-later) for the app's four ABIs and puts it into
# app/src/main/jniLibs/<abi>/ as libproot.so (the program) and libproot-loader.so (its loader).
# Files in the native library folder are the only ones Android 10+ lets an app execute, hence
# the lib*.so names. talloc and libandroid-shmem are linked statically from Termux's packages.
#
# Run in Linux (WSL): sh tools/native/build-proot.sh [path-to-android-ndk-r23c]
set -eu

NDK=${1:-$HOME/.mozbuild/android-ndk-r23c}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$HOME/dc-native
PROOT_REPO=https://github.com/termux/proot
PROOT_COMMIT=${PROOT_COMMIT:-}
TERMUX_REPO=https://packages.termux.dev/apt/termux-main

mkdir -p "$WORK"
cd "$WORK"
if [ ! -d proot ]; then
    git clone "$PROOT_REPO" proot
fi
cd proot
if [ -n "$PROOT_COMMIT" ]; then git checkout -q "$PROOT_COMMIT"; fi
echo "proot commit: $(git rev-parse HEAD)" | tee "$WORK/proot-commit.txt"
cd "$WORK"

HOST=$NDK/toolchains/llvm/prebuilt/linux-x86_64

# termux arch, NDK triple, API level, jniLibs ABI
for spec in "aarch64 aarch64-linux-android 21 arm64-v8a" \
            "arm armv7a-linux-androideabi 16 armeabi-v7a" \
            "x86_64 x86_64-linux-android 21 x86_64" \
            "i686 i686-linux-android 16 x86"; do
    set -- $spec
    ARCH=$1 TRIPLE=$2 API=$3 ABI=$4
    echo "=== $ABI"
    DEPS=$WORK/deps-$ARCH
    if [ ! -f "$DEPS/lib/libtalloc.a" ] || [ ! -f "$DEPS/lib/libandroid-shmem.a" ]; then
        mkdir -p "$DEPS/debs"
        curl -fsSL "$TERMUX_REPO/dists/stable/main/binary-$ARCH/Packages" -o "$DEPS/Packages"
        for pkg in libtalloc libtalloc-static libandroid-shmem libandroid-shmem-static; do
            file=$(awk -v p="$pkg" '$1=="Package:" {cur=$2} $1=="Filename:" && cur==p {print $2}' "$DEPS/Packages" | head -1)
            [ -n "$file" ] || { echo "no $pkg for $ARCH"; continue; }
            curl -fsSL "$TERMUX_REPO/$file" -o "$DEPS/debs/$pkg.deb"
            (cd "$DEPS/debs" && rm -rf x && mkdir x && cd x && ar x "../$pkg.deb" && tar xf data.tar.* )
            cp -r "$DEPS/debs/x/data/data/com.termux/files/usr/." "$DEPS/"
        done
    fi
    CC="$HOST/bin/$TRIPLE$API-clang"
    STRIP="$HOST/bin/llvm-strip"
    OBJCOPY="$HOST/bin/llvm-objcopy"
    OUT=$WORK/out-$ABI
    rm -rf "$WORK/build-$ABI"
    cp -r "$WORK/proot" "$WORK/build-$ABI"
    # droid_codology's changes (tools/native/patches): fork/vfork run as clone on Android, ...
    for patch in "$ROOT"/tools/native/patches/*.patch; do
        [ -f "$patch" ] || continue
        git -C "$WORK/build-$ABI" apply --whitespace=nowarn "$patch"
        echo "applied $(basename "$patch")"
    done
    cd "$WORK/build-$ABI/src"
    make -s clean >/dev/null 2>&1 || true
    # Static libs only: an executable in jniLibs can't count on Termux's shared ones.
    LOG=$WORK/make-$ABI.log
    # V=1: the full command lines, so a failure can be read in the log
    # Only the static libraries may be found: the program must not need Termux's .so files.
    rm -f "$DEPS"/lib/libtalloc.so* "$DEPS"/lib/libandroid-shmem.so
    # Flags through the environment, as Termux does: the makefile adds its own (-I. ...) with +=,
    # which flags given on make's command line would replace.
    # Android 7.0 functions the program and Termux's libtalloc use, for older Android versions.
    "$CC" -O2 -fPIE -c "$ROOT/tools/native/android-compat.c" -o "$WORK/compat-$ABI.o"
    if ! env CPPFLAGS="-I$DEPS/include -DARG_MAX=131072" CFLAGS="-O2 -fPIE" \
        LDFLAGS="-L$DEPS/lib $WORK/compat-$ABI.o -landroid-shmem -llog -ldl" \
        make V=1 CC="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$HOST/bin/llvm-objdump" \
        PROOT_UNBUNDLE_LOADER=/nonexistent \
        PROOT_WITH_LIBANDROID_SHMEM=true \
        proot loader/loader >"$LOG" 2>&1 || [ ! -f proot ]; then
        echo "BUILD FAILED for $ABI; the end of $LOG:"
        tail -40 "$LOG"
        exit 1
    fi
    mkdir -p "$ROOT/app/src/main/jniLibs/$ABI"
    "$STRIP" -o "$ROOT/app/src/main/jniLibs/$ABI/libproot.so" proot
    "$STRIP" -o "$ROOT/app/src/main/jniLibs/$ABI/libproot-loader.so" loader/loader
    if [ -f loader/loader-m32 ]; then "$STRIP" -o "$ROOT/app/src/main/jniLibs/$ABI/libproot-loader32.so" loader/loader-m32; fi
    ls -la "$ROOT/app/src/main/jniLibs/$ABI"
    cd "$WORK"
done
