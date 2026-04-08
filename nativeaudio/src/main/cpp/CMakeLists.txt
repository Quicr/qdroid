# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name.
project("nativeaudio")

# Include oboe
add_subdirectory(oboe)

# Fetch and build Opus
include(FetchContent)
FetchContent_Declare(
    opus
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG        v1.4
    GIT_SHALLOW    TRUE
)

set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "Don't build Opus programs")
set(OPUS_BUILD_TESTING OFF CACHE BOOL "Don't build Opus tests")
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "Don't install pkg-config")
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "Don't install CMake config")

FetchContent_MakeAvailable(opus)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
add_library(${CMAKE_PROJECT_NAME} SHARED
    # List C/C++ source files with relative paths to this CMakeLists.txt.
    nativeaudio.cpp)

# Specifies libraries CMake should link to your target library.
target_link_libraries(${CMAKE_PROJECT_NAME}
    # List libraries link to the target library
    android
    oboe
    log
    opus)

# Add oboe's include directory to our search path
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    oboe/include
    ${opus_SOURCE_DIR}/include)
