cmake_minimum_required(VERSION 3.22)
project(gxljni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libjxl is unusably slow without optimizations, so the native code is always
# built optimized — also for the debug APK.
foreach(lang C CXX)
  set(CMAKE_${lang}_FLAGS_DEBUG "-O2 -g0 -DNDEBUG")
  set(CMAKE_${lang}_FLAGS_RELEASE "-O2 -DNDEBUG")
  set(CMAKE_${lang}_FLAGS_RELWITHDEBINFO "-O2 -g -DNDEBUG")
endforeach()

if(NOT GXL_LIBJXL_DIR OR NOT EXISTS "${GXL_LIBJXL_DIR}/CMakeLists.txt")
  message(FATAL_ERROR
    "libjxl sources not found at '${GXL_LIBJXL_DIR}'. "
    "They are fetched by Gradle (app/build.gradle.kts); re-run the Gradle build "
    "or pass -Pgxl.libjxl.dir=/path/to/libjxl.")
endif()

# ---- libjxl: library only, static, no tools/tests/extra codecs -------------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(JPEGXL_STATIC OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_MANPAGES OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_JNI OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_SJPEG OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_OPENEXR OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_SKCMS ON CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_VIEWERS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_PLUGINS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_FUZZERS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_DEVTOOLS OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_TRANSCODE_JPEG ON CACHE BOOL "" FORCE)
set(JPEGXL_ENABLE_BOXES ON CACHE BOOL "" FORCE)
set(JPEGXL_BUNDLE_LIBPNG OFF CACHE BOOL "" FORCE)
set(JPEGXL_FORCE_SYSTEM_BROTLI OFF CACHE BOOL "" FORCE)
set(JPEGXL_FORCE_SYSTEM_HWY OFF CACHE BOOL "" FORCE)
set(JPEGXL_FORCE_SYSTEM_LCMS2 OFF CACHE BOOL "" FORCE)
set(JPEGXL_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)

add_subdirectory("${GXL_LIBJXL_DIR}" libjxl EXCLUDE_FROM_ALL)

# ---- JNI bridge ------------------------------------------------------------
add_library(gxljni SHARED
  jxl_codec.cc
  yuv.cc
  jni_bridge.cc
)

target_link_libraries(gxljni PRIVATE
  jxl
  jxl_threads
  jxl_cms
  jnigraphics
  log
)

target_compile_options(gxljni PRIVATE -Wall -Wextra -fvisibility=hidden)
# Strip unused code and keep the .so 16 KB page aligned (required on Android 15+).
target_link_options(gxljni PRIVATE
  -Wl,--gc-sections
  -Wl,-z,max-page-size=16384
)
