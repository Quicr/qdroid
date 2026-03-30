cmake_minimum_required(VERSION 3.22.1)

project("moq_jni")

# Enable FetchContent for libquicr and dependencies
include(FetchContent)

# BoringSSL support (boring2 branch as requested)
FetchContent_Declare(
    libquicr
    GIT_REPOSITORY https://github.com/Quicr/libquicr.git
    GIT_TAG        boring2
)

FetchContent_MakeAvailable(libquicr)

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
