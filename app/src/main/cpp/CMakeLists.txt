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

# BoringSSL support (boring2 branch as requested)
FetchContent_Declare(
    libquicr
    GIT_REPOSITORY https://github.com/Quicr/libquicr.git
    GIT_TAG        boring2
    GIT_SUBMODULES_RECURSE TRUE
)

# Fetch Kairos jitter buffer library
FetchContent_MakeAvailable(kairos)

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
)

target_link_libraries(moq_jni
    quicr
    android
    log
)

target_include_directories(moq_jni PRIVATE
    ${libquicr_SOURCE_DIR}/include
    ${libquicr_SOURCE_DIR}/src
    ${kairos_SOURCE_DIR}/api  # Kairos jitter buffer headers
)

# Optimization flags for jitter buffer performance
target_compile_options(moq_jni PRIVATE
    -O3
    -fvisibility=hidden
)
