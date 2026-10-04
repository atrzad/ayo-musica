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

# Chromaprint (LGPL-2.1, with its bundled KissFFT) for AcoustID: a fingerprint of the first two minutes.
set(FFT_LIB "kissfft" CACHE STRING "" FORCE)
set(BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
# Its KissFFT lookup searches the top project's folder; point it at the copy inside chromaprint's sources.
if(FETCHCONTENT_BASE_DIR)
    set(chromaprint_deps "${FETCHCONTENT_BASE_DIR}")
else()
    set(chromaprint_deps "${CMAKE_BINARY_DIR}/_deps")
endif()
set(KISSFFT_SOURCE_DIR "${chromaprint_deps}/chromaprint-src/src/3rdparty/kissfft" CACHE PATH "" FORCE)
FetchContent_Declare(chromaprint
    URL https://github.com/acoustid/chromaprint/archive/refs/tags/v1.6.1.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(chromaprint)

add_library(ayoprint SHARED chromaprint_jni.cpp)
target_link_libraries(ayoprint PRIVATE chromaprint log)
target_include_directories(ayoprint PRIVATE "${chromaprint_SOURCE_DIR}/src")
target_compile_options(ayoprint PRIVATE -O3)
