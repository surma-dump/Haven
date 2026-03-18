#!/usr/bin/env bash
# Build rdp-transport for Android targets and generate Kotlin bindings.
#
# Prerequisites:
#   cargo install cargo-ndk uniffi-bindgen-cli
#   rustup target add aarch64-linux-android x86_64-linux-android
#   ANDROID_NDK_HOME set (or detected from ~/Android/Sdk/ndk/*)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUST_DIR="$SCRIPT_DIR/../rust"
OUT_DIR="$SCRIPT_DIR/../jniLibs"
KOTLIN_DIR="$SCRIPT_DIR/../kotlin/sh/haven/rdp"

# Detect NDK if not set
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    NDK_DIR="$(ls -d "$HOME/Android/Sdk/ndk"/*/ 2>/dev/null | sort -V | tail -1)"
    if [ -n "$NDK_DIR" ]; then
        export ANDROID_NDK_HOME="$NDK_DIR"
        echo "Using NDK: $ANDROID_NDK_HOME"
    else
        echo "ERROR: ANDROID_NDK_HOME not set and no NDK found in ~/Android/Sdk/ndk/" >&2
        exit 1
    fi
fi

cd "$RUST_DIR"

echo "==> Building for arm64-v8a..."
cargo ndk -t arm64-v8a -o "$OUT_DIR" build --release

echo "==> Building for x86_64..."
cargo ndk -t x86_64 -o "$OUT_DIR" build --release

echo "==> Generating Kotlin bindings..."
mkdir -p "$KOTLIN_DIR"

# Use the host-built library for binding generation
# (need a host build for uniffi-bindgen)
cargo build --release 2>/dev/null || true

LIBPATH=""
if [ -f "target/release/librdp_transport.so" ]; then
    LIBPATH="target/release/librdp_transport.so"
elif [ -f "target/release/librdp_transport.dylib" ]; then
    LIBPATH="target/release/librdp_transport.dylib"
fi

if [ -n "$LIBPATH" ]; then
    uniffi-bindgen generate --library "$LIBPATH" \
        --language kotlin --out-dir "$KOTLIN_DIR" \
        --config uniffi.toml
    echo "==> Kotlin bindings generated in $KOTLIN_DIR"
else
    echo "WARN: Could not generate Kotlin bindings (no host library found)"
    echo "      Run 'cargo build --release' on the host first"
fi

echo "==> Done. Libraries in $OUT_DIR:"
find "$OUT_DIR" -name "*.so" -type f
