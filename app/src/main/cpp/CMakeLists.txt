cmake_minimum_required(VERSION 3.22.1)
project("neutts_bridge")

# NeuTTS Bridge - JNI wrapper for llama.cpp
# This build integrates llama.cpp for GGUF model token generation

# Find system libraries
find_library(log-lib log)

# llama.cpp is vendored in-tree; the bridge requires it to build.
#
# GGML_NATIVE is forced off when cross-compiling (always true for the NDK), so
# without an explicit arch here ggml-cpu falls back to NDK clang's "generic"
# armv8-a+neon baseline -- no dotprod/i8mm -- which forces the Q4_0/Q8_0 dot
# product kernels onto a software-emulated fallback (~order-of-magnitude
# slower). minSdk 28 devices are ARMv8.2+ almost universally, so dotprod is a
# safe baseline; i8mm needs ~2021+ SoCs but ggml only uses it opportunistically.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_TOOLS OFF)
set(LLAMA_BUILD_COMMON OFF)
set(LLAMA_BUILD_APP OFF)
set(LLAMA_SUBPROCESS OFF)
set(LLAMA_OPENSSL OFF)
set(LLAMA_LLGUIDANCE OFF)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_subdirectory(llama.cpp)

# espeak-ng is vendored in-tree for on-device phonemization (see phonemizer.cpp).
# Tests/docs/data-recompilation are disabled: ENABLE_TESTS and COMPILE_INTONATIONS
# already auto-skip on cross-compile, but set them explicitly for clarity. The
# phoneme/dict data itself is prebuilt on the host and shipped as an asset
# (app/src/main/assets/espeak-ng-data), not compiled by this NDK build.
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_INTONATIONS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(espeak-ng)

# Our bridge library
add_library(neutts_bridge SHARED
    neutts_bridge.cpp
)

# Include directories
target_include_directories(neutts_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
)

# Enable 16KB page alignment for Android 15+ (API 35).
# This is required for devices running with 16KB page size. Applied
# unconditionally: NDK r27+ already defaults to 16KB alignment, but forcing
# -z max-page-size=16384 guarantees it regardless of NDK/platform changes.
target_link_options(neutts_bridge PRIVATE
    "LINKER:-z,max-page-size=16384"
)

# Link libraries
target_link_libraries(neutts_bridge
    ${log-lib}
    llama
)

# Phonemizer bridge library (espeak-ng JNI wrapper)
add_library(phonemizer SHARED
    phonemizer.cpp
)

target_include_directories(phonemizer PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/espeak-ng/src/include
)

target_link_libraries(phonemizer
    ${log-lib}
    espeak-ng
    espeak-ng-config
)

# Apply 16KB page alignment to phonemizer as well
target_link_options(phonemizer PRIVATE
    "LINKER:-z,max-page-size=16384"
)
