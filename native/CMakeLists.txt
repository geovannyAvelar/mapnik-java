cmake_minimum_required(VERSION 3.16)
project(mapnik_c CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Mapnik installed with CMake (or vcpkg) ships a package config; Mapnik built with
# SCons ships mapnik-config instead. Try the package first, then mapnik-config.
find_package(mapnik CONFIG QUIET)

set(MAPNIK_FOUND_VIA "")
if(TARGET mapnik::mapnik)
  set(MAPNIK_FOUND_VIA "package")
  set(INSTALLED_MAPNIK "${mapnik_VERSION}")
else()
  find_program(MAPNIK_CONFIG mapnik-config)
  if(NOT MAPNIK_CONFIG)
    message(FATAL_ERROR "Mapnik not found. Install it with CMake (find_package(mapnik)) or put mapnik-config on PATH.")
  endif()
  set(MAPNIK_FOUND_VIA "mapnik-config")
  execute_process(COMMAND ${MAPNIK_CONFIG} --version
                  OUTPUT_VARIABLE INSTALLED_MAPNIK OUTPUT_STRIP_TRAILING_WHITESPACE)
endif()
message(STATUS "Mapnik ${INSTALLED_MAPNIK} (via ${MAPNIK_FOUND_VIA})")

# Warn when the installed Mapnik differs in major.minor from the one this
# release targets (mapnik.version in ../gradle.properties).
file(STRINGS ${CMAKE_CURRENT_SOURCE_DIR}/../gradle.properties TARGET_LINE REGEX "^mapnik\\.version=")
string(REGEX REPLACE "^mapnik\\.version=" "" TARGET_MAPNIK "${TARGET_LINE}")
string(REGEX MATCH "^[0-9]+\\.[0-9]+" INSTALLED_MINOR "${INSTALLED_MAPNIK}")
string(REGEX MATCH "^[0-9]+\\.[0-9]+" TARGET_MINOR "${TARGET_MAPNIK}")
if(INSTALLED_MINOR AND NOT INSTALLED_MINOR STREQUAL TARGET_MINOR)
  message(WARNING "Installed Mapnik ${INSTALLED_MAPNIK}, but this mapnik-java release targets ${TARGET_MAPNIK}. The build may fail or behave differently.")
endif()

add_library(mapnik_c SHARED
  src/runtime.cpp src/diagnostics.cpp src/map.cpp src/layer.cpp src/datasource.cpp src/feature.cpp src/geometry_out.cpp src/projection.cpp src/image.cpp src/render.cpp)

if(MAPNIK_FOUND_VIA STREQUAL "package")
  target_link_libraries(mapnik_c PRIVATE mapnik::mapnik)
else()
  function(mapnik_config out)
    execute_process(COMMAND ${MAPNIK_CONFIG} ${ARGN}
                    OUTPUT_VARIABLE result OUTPUT_STRIP_TRAILING_WHITESPACE)
    string(REPLACE "\n" " " result "${result}")
    separate_arguments(result)
    set(${out} ${result} PARENT_SCOPE)
  endfunction()

  mapnik_config(MAPNIK_CFLAGS --defines --includes --dep-includes)
  mapnik_config(MAPNIK_LIBS   --libs --ldflags --dep-libs)
  target_compile_options(mapnik_c PRIVATE ${MAPNIK_CFLAGS})

  target_link_libraries(mapnik_c PRIVATE ${MAPNIK_LIBS})
endif()

# Fail the build, not the first call, if a symbol is missing.
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_options(mapnik_c PRIVATE -Wl,--no-undefined)
endif()

# Drop lib next to where Gradle expects it.
set_target_properties(mapnik_c PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../build/native)
