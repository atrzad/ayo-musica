# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file LICENSE.rst or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION ${CMAKE_VERSION}) # this file comes with cmake

# If CMAKE_DISABLE_SOURCE_CHANGES is set to true and the source directory is an
# existing directory in our source tree, calling file(MAKE_DIRECTORY) on it
# would cause a fatal error, even though it would be a no-op.
if(NOT EXISTS "/home/ayo/Projects/ayo-musica/android/app/.cxx/Release/3i431kh2/x86_64/_deps/whisper-src")
  file(MAKE_DIRECTORY "/home/ayo/Projects/ayo-musica/android/app/.cxx/Release/3i431kh2/x86_64/_deps/whisper-src")
endif()
file(MAKE_DIRECTORY
  "/home/ayo/Projects/ayo-musica/android/app/.cxx/Release/3i431kh2/x86_64/_deps/whisper-build"
  "/home/ayo/Projects/ayo-musica/android/app/.cxx/Release/3i431kh2/x86_64/_deps/whisper-subbuild/whisper-populate-prefix"
  "/home/ayo/Projects/ayo-musica/android/app/.cxx/Release/3i431kh2/x86_64/_deps/whisper-subbuild/whisper-populate-prefix/tmp"
  "/home/ayo/Projects/ayo-musica/android/app/.cxx/Release/3i431kh2/x86_64/_deps/whisper-subbuild/whisper-populate-prefix/src/whisper-populate-stamp"
  "/home/ayo/Projects/ayo-musica/android/app/.cxx/Release/3i431kh2/x86_64/_deps/whisper-subbuild/whisper-populate-prefix/src"
  "/home/ayo/Projects/ayo-musica/android/app/.cxx/Release/3i431kh2/x86_64/_deps/whisper-subbuild/whisper-populate-prefix/src/whisper-populate-stamp"
)

set(configSubDirs )
foreach(subDir IN LISTS configSubDirs)
    file(MAKE_DIRECTORY "/home/ayo/Projects/ayo-musica/android/app/.cxx/Release/3i431kh2/x86_64/_deps/whisper-subbuild/whisper-populate-prefix/src/whisper-populate-stamp/${subDir}")
endforeach()
if(cfgdir)
  file(MAKE_DIRECTORY "/home/ayo/Projects/ayo-musica/android/app/.cxx/Release/3i431kh2/x86_64/_deps/whisper-subbuild/whisper-populate-prefix/src/whisper-populate-stamp${cfgdir}") # cfgdir has leading slash
endif()
