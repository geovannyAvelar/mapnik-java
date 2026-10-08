# vcpkg triplet for the portable Linux bundle: shared libraries, release only, built with the toolchain below.
set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE dynamic)
set(VCPKG_CMAKE_SYSTEM_NAME Linux)
set(VCPKG_BUILD_TYPE release)
set(VCPKG_FIXUP_ELF_RPATH ON)
set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${CMAKE_CURRENT_LIST_DIR}/toolchain-gcc-toolset.cmake")

# abseil and utf8-range are static libraries that protobuf, a shared library, links in: they must be position independent.
if(PORT STREQUAL "abseil" OR PORT STREQUAL "utf8-range")
  set(VCPKG_C_FLAGS "-fPIC")
  set(VCPKG_CXX_FLAGS "-fPIC")
endif()
