# SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
# SPDX-License-Identifier: BSD-2-Clause
#
# Host (non-Android) unit tests for the native VAD logic.
#
# These are intentionally NOT part of the Android/Gradle externalNativeBuild — that
# build cross-compiles for the device, so it can't run host tests. Build and run
# these directly on a dev machine or CI host:
#
#   cmake -S nativeaudio/src/test/cpp -B build/vad-tests
#   cmake --build build/vad-tests
#   ctest --test-dir build/vad-tests --output-on-failure
#
# Or without CMake at all:
#
#   c++ -std=c++17 -I nativeaudio/src/main/cpp \
#       nativeaudio/src/test/cpp/vad_gate_test.cpp -o vad_gate_test && ./vad_gate_test

cmake_minimum_required(VERSION 3.22.1)
project(nativeaudio_host_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

enable_testing()

add_executable(vad_gate_test vad_gate_test.cpp)
target_include_directories(vad_gate_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

add_test(NAME vad_gate_test COMMAND vad_gate_test)
