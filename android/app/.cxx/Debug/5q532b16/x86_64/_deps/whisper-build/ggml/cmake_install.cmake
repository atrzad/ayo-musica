# Install script for directory: /home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "/usr/local")
endif()
string(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
if(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  if(BUILD_TYPE)
    string(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  else()
    set(CMAKE_INSTALL_CONFIG_NAME "Debug")
  endif()
  message(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
endif()

# Set the component getting installed.
if(NOT CMAKE_INSTALL_COMPONENT)
  if(COMPONENT)
    message(STATUS "Install component: \"${COMPONENT}\"")
    set(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  else()
    set(CMAKE_INSTALL_COMPONENT)
  endif()
endif()

# Install shared libraries without execute permission?
if(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)
  set(CMAKE_INSTALL_SO_NO_EXE "0")
endif()

# Is this installation the result of a crosscompile?
if(NOT DEFINED CMAKE_CROSSCOMPILING)
  set(CMAKE_CROSSCOMPILING "TRUE")
endif()

# Set path to fallback-tool for dependency-resolution.
if(NOT DEFINED CMAKE_OBJDUMP)
  set(CMAKE_OBJDUMP "/home/ayo/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump")
endif()

if(NOT CMAKE_INSTALL_LOCAL_ONLY)
  # Include the install script for the subdirectory.
  include("/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-build/ggml/src/cmake_install.cmake")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-build/ggml/src/libggml.a")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include" TYPE FILE FILES
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-cpu.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-alloc.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-backend.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-blas.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-cann.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-cpp.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-cuda.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-opt.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-metal.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-rpc.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-virtgpu.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-sycl.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-vulkan.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-webgpu.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-zendnn.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/ggml-openvino.h"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-src/ggml/include/gguf.h"
    )
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-build/ggml/src/libggml-base.a")
endif()

if(CMAKE_INSTALL_COMPONENT STREQUAL "Unspecified" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/ggml" TYPE FILE FILES
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-build/ggml/ggml-config.cmake"
    "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-build/ggml/ggml-config-version.cmake"
    )
endif()

string(REPLACE ";" "\n" CMAKE_INSTALL_MANIFEST_CONTENT
       "${CMAKE_INSTALL_MANIFEST_FILES}")
if(CMAKE_INSTALL_LOCAL_ONLY)
  file(WRITE "/home/ayo/Projects/ayo-musica/android/app/.cxx/Debug/5q532b16/x86_64/_deps/whisper-build/ggml/install_local_manifest.txt"
     "${CMAKE_INSTALL_MANIFEST_CONTENT}")
endif()
