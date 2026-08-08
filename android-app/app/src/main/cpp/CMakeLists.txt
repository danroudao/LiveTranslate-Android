cmake_minimum_required(VERSION 3.22)
project(llmengine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp 静态库（NDK 交叉编译预置，见 tools/ndk-build.sh）
add_library(llama STATIC IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libllama.a)

add_library(ggml STATIC IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libggml.a)

add_library(ggml-base STATIC IMPORTED)
set_target_properties(ggml-base PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libggml-base.a)

add_library(ggml-cpu STATIC IMPORTED)
set_target_properties(ggml-cpu PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libggml-cpu.a)

add_library(llmengine SHARED jni_llm.cpp)

target_include_directories(llmengine PRIVATE ${CMAKE_SOURCE_DIR}/llama-include)

target_link_libraries(llmengine
    llama
    ggml-cpu
    ggml-base
    ggml
    android
    log
    m
    atomic
)
