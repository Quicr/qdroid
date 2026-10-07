# libfvad — WebRTC Voice Activity Detection as a standalone C library (BSD-3).
# Vendored from https://github.com/dpirch/libfvad (tag v1.0, commit 99fd86b).
# Upstream ships only autotools; this CMakeLists builds the sources as a static
# library so the Android nativeaudio target can link it directly.

cmake_minimum_required(VERSION 3.22.1)
project(fvad C)

add_library(fvad STATIC
    src/fvad.c
    src/signal_processing/division_operations.c
    src/signal_processing/energy.c
    src/signal_processing/get_scaling_square.c
    src/signal_processing/resample_48khz.c
    src/signal_processing/resample_by_2_internal.c
    src/signal_processing/resample_fractional.c
    src/signal_processing/spl_inl.c
    src/vad/vad_core.c
    src/vad/vad_filterbank.c
    src/vad/vad_gmm.c
    src/vad/vad_sp.c)

# Public header (fvad.h) for consumers; private dirs cover the mixed
# intra-library include styles ("vad/x.h", "x.h", "signal_processing_library.h").
target_include_directories(fvad
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
        ${CMAKE_CURRENT_SOURCE_DIR}/src/vad
        ${CMAKE_CURRENT_SOURCE_DIR}/src/signal_processing)

target_compile_definitions(fvad PRIVATE _GNU_SOURCE)
set_target_properties(fvad PROPERTIES C_STANDARD 11 POSITION_INDEPENDENT_CODE ON)
