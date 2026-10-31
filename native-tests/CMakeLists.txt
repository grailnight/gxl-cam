# Host (desktop) build of the native codec layer + tests. Used by CI.
#   cmake -S native-tests -B build-host -DGXL_LIBJXL_DIR=.deps/libjxl-v0.12.0
#   cmake --build build-host && build-host/codec_test samples/*.jpg
cmake_minimum_required(VERSION 3.22)
project(gxl_native_tests C CXX)
set(CMAKE_CXX_STANDARD 17)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
foreach(opt TOOLS DOXYGEN MANPAGES BENCHMARK EXAMPLES JNI SJPEG OPENEXR VIEWERS
            PLUGINS FUZZERS DEVTOOLS TCMALLOC)
  set(JPEGXL_ENABLE_${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(JPEGXL_ENABLE_SKCMS ON CACHE BOOL "" FORCE)
set(JPEGXL_BUNDLE_LIBPNG OFF CACHE BOOL "" FORCE)
add_subdirectory("${GXL_LIBJXL_DIR}" libjxl EXCLUDE_FROM_ALL)

set(APP_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp")
add_executable(codec_test codec_test.cc "${APP_CPP}/jxl_codec.cc" "${APP_CPP}/yuv.cc")
target_include_directories(codec_test PRIVATE "${APP_CPP}")
target_compile_options(codec_test PRIVATE -Wall -Wextra)
target_link_libraries(codec_test PRIVATE jxl jxl_threads jxl_cms)
