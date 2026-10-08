# The manylinux_2_28 image has two compilers: the system's old GCC 8 and GCC Toolset's newer one, which links
# against the system's C++ runtime and adds the few newer pieces statically, so the result runs on any system
# with glibc 2.28 and libstdc++ from GCC 8 or later. Use the Toolset's, always, for every dependency.
find_program(MAPNIK_GCC gcc PATHS /opt/rh/gcc-toolset-14/root/usr/bin /opt/rh/gcc-toolset-13/root/usr/bin NO_DEFAULT_PATH)
find_program(MAPNIK_GXX g++ PATHS /opt/rh/gcc-toolset-14/root/usr/bin /opt/rh/gcc-toolset-13/root/usr/bin NO_DEFAULT_PATH)
if(NOT MAPNIK_GCC OR NOT MAPNIK_GXX)
  message(FATAL_ERROR "GCC Toolset not found: this triplet is for the manylinux_2_28 container")
endif()
set(CMAKE_C_COMPILER "${MAPNIK_GCC}" CACHE FILEPATH "" FORCE)
set(CMAKE_CXX_COMPILER "${MAPNIK_GXX}" CACHE FILEPATH "" FORCE)
