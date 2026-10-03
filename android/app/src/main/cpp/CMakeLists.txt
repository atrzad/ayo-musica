# whisper.cpp for syncing plain lyrics by voice, built from its release (not copied into the repo).
cmake_minimum_required(VERSION 3.22)
project(ayowhisper C CXX)

include(FetchContent)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# Fast paths for each processor: ARMv8.2 (fp16 + dot product, phones since ~2018; the app checks the
# phone has them before syncing) and AVX + F16C on x86_64 (the emulator).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    foreach(feature GGML_AVX GGML_F16C)
        set(${feature} ON CACHE BOOL "" FORCE)
    endforeach()
endif()
FetchContent_Declare(whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(whisper)

add_library(ayowhisper SHARED whisper_jni.cpp)
target_link_libraries(ayowhisper PRIVATE whisper log)
target_compile_options(ayowhisper PRIVATE -O3)
