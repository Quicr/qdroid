# Apply patches to fix Android build issues in loc-cpp
message(STATUS "Applying patches to loc-cpp...")

# Patch: Lower CMake version requirement from 3.24 to 3.22.1 for Android NDK compatibility
set(LOC_CMAKE "${loc-cpp_SOURCE_DIR}/CMakeLists.txt")
if(EXISTS "${LOC_CMAKE}")
    file(READ "${LOC_CMAKE}" LOC_CONTENT)

    # Check if already patched
    string(FIND "${LOC_CONTENT}" "# Patched for Android NDK" ALREADY_PATCHED)

    if(ALREADY_PATCHED EQUAL -1)
        # Replace cmake_minimum_required to lower version
        string(REGEX REPLACE
            "cmake_minimum_required\\(VERSION [0-9]+\\.[0-9]+[^)]*\\)"
            "cmake_minimum_required(VERSION 3.22.1)  # Patched for Android NDK (original: 3.24+)"
            LOC_CONTENT "${LOC_CONTENT}")

        file(WRITE "${LOC_CMAKE}" "${LOC_CONTENT}")
        message(STATUS "Patch applied: Lowered CMake version requirement for Android NDK")
    else()
        message(STATUS "Patch already applied")
    endif()
else()
    message(WARNING "loc-cpp CMakeLists.txt not found: ${LOC_CMAKE}")
endif()

message(STATUS "loc-cpp patches processing complete")
