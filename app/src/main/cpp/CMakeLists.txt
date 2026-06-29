cmake_minimum_required(VERSION 3.22.1)

project("moq_jni")

# Set C++20 for Kairos jitter buffer support
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enable FetchContent for libquicr and dependencies
include(FetchContent)

set(CRYPTO "BORINGSSL" CACHE STRING "Crypto backend " FORCE)

# Fetch Kairos jitter buffer library
FetchContent_Declare(
    kairos
    GIT_REPOSITORY git@github.com:Quicr/kairos.git
    GIT_TAG        main
)

# Fetch LOC (Low Overhead Container) library
FetchContent_Declare(
    loc-cpp
    GIT_REPOSITORY git@github.com:Quicr/loc-cpp.git
    GIT_TAG        main
)

# BoringSSL support (boring2 branch as requested)
FetchContent_Declare(
    libquicr
    GIT_REPOSITORY git@github.com:Quicr/libquicr.git
    GIT_TAG        boring2
    GIT_SUBMODULES_RECURSE TRUE
)

# Disable tests, benchmarks, and examples for Kairos to avoid cross-compilation execution errors
set(BUILD_TESTING OFF CACHE BOOL "Disable Kairos tests" FORCE)
set(KAIROS_BUILD_TESTS OFF CACHE BOOL "Disable Kairos tests" FORCE)
set(KAIROS_BUILD_BENCHMARKS OFF CACHE BOOL "Disable Kairos benchmarks" FORCE)
set(KAIROS_BUILD_EXAMPLES OFF CACHE BOOL "Disable Kairos examples" FORCE)

# Disable tests and examples for LOC to avoid cross-compilation execution errors
set(LOC_BUILD_TESTS OFF CACHE BOOL "Disable loc-cpp tests" FORCE)
set(LOC_BUILD_EXAMPLES OFF CACHE BOOL "Disable loc-cpp examples" FORCE)

# Fetch Kairos jitter buffer library
FetchContent_MakeAvailable(kairos)

# Fetch LOC library but don't make available yet (so we can patch it)
FetchContent_GetProperties(loc-cpp)
if(NOT loc-cpp_POPULATED)
    FetchContent_Populate(loc-cpp)

    # Apply patch to fix CMake version requirement for Android NDK
    include(${CMAKE_CURRENT_SOURCE_DIR}/patch_loc.cmake)

    # Now add the subdirectory
    add_subdirectory(${loc-cpp_SOURCE_DIR} ${loc-cpp_BINARY_DIR})
endif()

# Fetch libquicr but don't make available yet (so we can patch it)
FetchContent_GetProperties(libquicr)
if(NOT libquicr_POPULATED)
    FetchContent_Populate(libquicr)

    # Apply patch to fix boring2 branch CMakeLists.txt bug
    include(${CMAKE_CURRENT_SOURCE_DIR}/patch_libquicr.cmake)

    # Now add the subdirectory
    add_subdirectory(${libquicr_SOURCE_DIR} ${libquicr_BINARY_DIR})
endif()

add_library(moq_jni SHARED
    moq_jni.cpp
    video_jitter_buffer.cpp
    audio_jitter_buffer.cpp
    video_framer.cpp
    audio_framer.cpp
    native_vad.cpp
    loc_wrapper.cpp
)

target_link_libraries(moq_jni
    quicr
    android
    log
    loc
)

target_include_directories(moq_jni PRIVATE
    ${libquicr_SOURCE_DIR}/include
    ${libquicr_SOURCE_DIR}/src
    ${kairos_SOURCE_DIR}/api  # Kairos jitter buffer headers
    ${loc-cpp_SOURCE_DIR}/include  # LOC container headers
)

# Optimization flags for jitter buffer performance
target_compile_options(moq_jni PRIVATE
    -O3
    -fvisibility=hidden
)
