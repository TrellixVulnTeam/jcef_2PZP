#!/bin/bash
# Copyright 2000-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "$0")" &>/dev/null && pwd)

source "$script_dir/set_env.sh"

OUT_DIR="$JCEF_ROOT_DIR/jcef_build"

if [ "${1:-}" == "clean" ]; then
  echo "*** delete $OUT_DIR..."
  rm -rf "$OUT_DIR"
  exit 0
fi
mkdir -p "$OUT_DIR"

echo "*** create modular jogl..."
bash "$JB_TOOLS_DIR"/modular-jogl.sh

echo "*** run cmake [TARGET=$TARGET_ARCH]..."
cd "$OUT_DIR" || exit 1
cmake -G "Xcode" -DPROJECT_ARCH="$TARGET_ARCH" ..

echo "*** run xcodebuild..."
xcodebuild -configuration Release

echo "*** change @rpath in libjcef.dylib..."
cd "$OUT_DIR"/native/Release || exit 1
install_name_tool -change @rpath/libjvm.dylib @loader_path/server/libjvm.dylib libjcef.dylib
install_name_tool -change @rpath/libjawt.dylib @loader_path/libjawt.dylib libjcef.dylib

if [ "$TARGET_ARCH" == "arm64" ]; then
  if otool -L libjcef.dylib | grep -q JavaNativeFoundation; then
    JNF_RPATH="$(otool -L libjcef.dylib | grep JavaNativeFoundation | awk '{print $1}')"
    install_name_tool -change "$JNF_RPATH" @loader_path/../../Frameworks/JavaNativeFoundation.framework/JavaNativeFoundation libjcef.dylib
  fi
fi

cp libjcef.dylib modular-sdk/modules_libs/jcef/
cp libjcef.dylib jcef_app.app/Contents/Java/
