cmake_minimum_required(VERSION 3.22.1)

project("moq_jni")

# Enable FetchContent for libquicr and dependencies
include(FetchContent)

set(CRYPTO "BORINGSSL" CACHE STRING "Crypto backend " FORCE)
# BoringSSL support (boring2 branch as requested)
FetchContent_Declare(
    libquicr
    GIT_REPOSITORY https://github.com/Quicr/libquicr.git
    GIT_TAG        boring2
    GIT_SUBMODULES_RECURSE TRUE
)

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
)

target_link_libraries(moq_jni
    quicr
    android
    log
)

target_include_directories(moq_jni PRIVATE
    ${libquicr_SOURCE_DIR}/include
    ${libquicr_SOURCE_DIR}/src
)
