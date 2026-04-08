# Apply patches to fix Android build issues in libquicr boring2 branch
message(STATUS "Applying patches to libquicr...")

# Patch 1: Fix picotls dependencies in libquicr/dependencies/CMakeLists.txt
set(DEPS_CMAKE "${libquicr_SOURCE_DIR}/dependencies/CMakeLists.txt")
if(EXISTS "${DEPS_CMAKE}")
    file(READ "${DEPS_CMAKE}" DEPS_CONTENT)

    # Check if already patched
    string(FIND "${DEPS_CONTENT}" "# add_subdirectory(picotls)  # Patched" ALREADY_PATCHED)

    if(ALREADY_PATCHED EQUAL -1)
        # Comment out add_subdirectory(picotls) and early dependency declarations
        string(REPLACE
            "    add_subdirectory(picotls)"
            "    # add_subdirectory(picotls)  # Patched - FetchContent handles this"
            DEPS_CONTENT "${DEPS_CONTENT}")

        string(REPLACE
            "    add_dependencies(picotls-openssl ssl decrepit crypto)"
            "    # add_dependencies(picotls-openssl ssl decrepit crypto)  # Moved after FetchContent"
            DEPS_CONTENT "${DEPS_CONTENT}")

        string(REPLACE
            "    add_dependencies(picotls-core decrepit crypto ssl)"
            "    # add_dependencies(picotls-core decrepit crypto ssl)  # Moved after FetchContent"
            DEPS_CONTENT "${DEPS_CONTENT}")

        string(REPLACE
            "    add_dependencies(test-openssl.t decrepit crypto ssl)"
            "    # add_dependencies(test-openssl.t decrepit crypto ssl)  # Moved after FetchContent"
            DEPS_CONTENT "${DEPS_CONTENT}")

        # Add dependencies after FetchContent_MakeAvailable(picotls)
        string(REPLACE
            "    FetchContent_MakeAvailable(picotls)"
            "    FetchContent_MakeAvailable(picotls)\n\n    # Add dependencies after FetchContent makes targets available\n    add_dependencies(picotls-openssl ssl decrepit crypto)\n    add_dependencies(picotls-core decrepit crypto ssl)\n    if(TARGET test-openssl.t)\n        add_dependencies(test-openssl.t decrepit crypto ssl)\n    endif()"
            DEPS_CONTENT "${DEPS_CONTENT}")

        file(WRITE "${DEPS_CMAKE}" "${DEPS_CONTENT}")
        message(STATUS "Patch 1 applied: Fixed picotls dependencies")
    else()
        message(STATUS "Patch 1 already applied")
    endif()
else()
    message(WARNING "Dependencies CMakeLists.txt not found: ${DEPS_CMAKE}")
endif()

# Patch 2: Fix picoquic install export issues
set(PICOQUIC_CMAKE "${libquicr_SOURCE_DIR}/dependencies/picoquic/CMakeLists.txt")
if(EXISTS "${PICOQUIC_CMAKE}")
    file(READ "${PICOQUIC_CMAKE}" PICOQUIC_CONTENT)

    # Check if already patched
    string(FIND "${PICOQUIC_CONTENT}" "# Commented out for Android build" ALREADY_PATCHED2)

    if(ALREADY_PATCHED2 EQUAL -1)
        # Comment out install(EXPORT picoquic-targets ...)
        string(REPLACE
            "install(EXPORT picoquic-targets\n    FILE picoquic-targets.cmake\n    NAMESPACE picoquic::\n    DESTINATION \${CMAKE_INSTALL_LIBDIR}/cmake/picoquic)"
            "# Commented out for Android build - export dependency issue\n# install(EXPORT picoquic-targets\n#     FILE picoquic-targets.cmake\n#     NAMESPACE picoquic::\n#     DESTINATION \${CMAKE_INSTALL_LIBDIR}/cmake/picoquic)"
            PICOQUIC_CONTENT "${PICOQUIC_CONTENT}")

        file(WRITE "${PICOQUIC_CMAKE}" "${PICOQUIC_CONTENT}")
        message(STATUS "Patch 2 applied: Fixed picoquic install export")
    else()
        message(STATUS "Patch 2 already applied")
    endif()
else()
    message(WARNING "Picoquic CMakeLists.txt not found: ${PICOQUIC_CMAKE}")
endif()

message(STATUS "Patches processing complete")
