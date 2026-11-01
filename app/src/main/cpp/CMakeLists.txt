cmake_minimum_required(VERSION 3.28)
project(polarlsl LANGUAGES C CXX)

# liblsl is built from source for the current Android ABI and linked statically into
# libpolarlsl.so, so the APK only carries one native library per ABI.
set(LIBLSL_SOURCE_DIR "" CACHE PATH "Local liblsl checkout; empty = download LIBLSL_VERSION")
set(LIBLSL_VERSION "1.17.5" CACHE STRING "liblsl release to download")

set(LSL_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(LSL_DEBUGLOG OFF CACHE BOOL "" FORCE)
# LTO breaks thread_local on 32-bit ARM below API 29 (link-time codegen loses emulated TLS).
set(LSL_OPTIMIZATIONS OFF CACHE BOOL "" FORCE)

include(FetchContent)
# Declared before liblsl so this (tarball, no git needed) wins over liblsl's own git declaration.
FetchContent_Declare(pugixml
    URL https://github.com/zeux/pugixml/releases/download/v1.15/pugixml-1.15.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    EXCLUDE_FROM_ALL
)

if(LIBLSL_SOURCE_DIR)
    message(STATUS "Using local liblsl at ${LIBLSL_SOURCE_DIR}")
    add_subdirectory(${LIBLSL_SOURCE_DIR} ${CMAKE_BINARY_DIR}/liblsl EXCLUDE_FROM_ALL)
else()
    set(lslgitrevision "v${LIBLSL_VERSION}" CACHE STRING "" FORCE)
    set(lslgitbranch "release" CACHE STRING "" FORCE)
    FetchContent_Declare(liblsl
        URL https://github.com/sccn/liblsl/archive/refs/tags/v${LIBLSL_VERSION}.tar.gz
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        EXCLUDE_FROM_ALL
    )
    FetchContent_MakeAvailable(liblsl)
endif()

add_library(polarlsl SHARED lsl_jni.cpp)
target_link_libraries(polarlsl PRIVATE lsl android log)
# Required for devices with 16 KB memory pages (Android 15+).
target_link_options(polarlsl PRIVATE "-Wl,-z,max-page-size=16384")
