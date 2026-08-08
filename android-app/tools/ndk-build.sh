#!/bin/bash
# NDK 交叉编译 llama.cpp 静态库（Android ABI：x86_64 模拟器 / arm64-v8a 真机）
# 用法：tools/ndk-build.sh  （在 llm-server 容器内执行，NDK 已解压到 /opt/android-ndk-r26d）
# 产物拷贝到 app/src/main/cpp/prebuilt/<abi>/
set -e
LLAMA=/opt/llama.cpp
NDK=/opt/android-ndk-r26d
OUT=/workspace_lt/app/src/main/cpp

for ABI in x86_64 arm64-v8a; do
  echo "=== building $ABI ==="
  cd $LLAMA
  EXTRA=""
  if [ "$ABI" = "x86_64" ]; then
    # 模拟器 guest CPU 无 AVX2，仅 AVX
    EXTRA="-DGGML_AVX2=OFF -DGGML_AVX=ON"
  fi
  cmake -B build-android-$ABI \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF -DBUILD_SHARED_LIBS=OFF \
    -DGGML_NATIVE=OFF $EXTRA \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF > /dev/null
  cmake --build build-android-$ABI --target llama -j16 > /dev/null 2>&1 || cmake --build build-android-$ABI --target llama -j8
  mkdir -p $OUT/prebuilt/$ABI $OUT/llama-include
  cp build-android-$ABI/src/libllama.a $OUT/prebuilt/$ABI/
  cp build-android-$ABI/ggml/src/libggml.a build-android-$ABI/ggml/src/libggml-base.a build-android-$ABI/ggml/src/libggml-cpu.a $OUT/prebuilt/$ABI/
  cp -r $LLAMA/include/* $OUT/llama-include/
  echo "  -> $OUT/prebuilt/$ABI"
done
echo ALL_DONE
