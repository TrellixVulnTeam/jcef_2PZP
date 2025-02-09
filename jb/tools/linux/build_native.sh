#!/bin/bash
# Copyright 2000-2022 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
set -xeuo pipefail

script_dir=$(cd -- "$(dirname -- "$0")" &>/dev/null && pwd)

source "$script_dir/set_env.sh"

OUT_DIR="$JCEF_ROOT_DIR/jcef_build"

if [ "${1:-}" == "clean" ]; then
  echo "*** delete $OUT_DIR..."
  rm -rf "$OUT_DIR"
  exit 0
fi
mkdir -p "$OUT_DIR"

cd "$JCEF_ROOT_DIR" || exit 1

# workaround python failure in docker
git checkout tools/make_version_header.py

if [ "${TARGET_ARCH}" == "x86_64" ]; then
  echo "*** create modular jogl..."
  bash "$JB_TOOLS_DIR"/modular-jogl.sh
fi

echo "*** run cmake [TARGET=$TARGET_ARCH]..."
cd "$OUT_DIR" || exit 1

additional_cmake=""
if [ -n "${CEF_VERSION:-}" ]; then
  additional_cmake="$additional_cmake -DCEF_VERSION=$CEF_VERSION"
fi

if [ -n "${CEF_DONT_DOWNLOAD:-}" ]; then
  additional_cmake="$additional_cmake -DCEF_DONT_DOWNLOAD=$CEF_DONT_DOWNLOAD"
fi

cmake -G "Unix Makefiles" -DPROJECT_ARCH="$TARGET_ARCH" -DCMAKE_BUILD_TYPE=Release $additional_cmake ..

echo "*** run make..."
make -j4
