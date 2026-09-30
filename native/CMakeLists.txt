cmake_minimum_required(VERSION 3.16)
project(mapnik_c CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_program(MAPNIK_CONFIG mapnik-config REQUIRED)

function(mapnik_config out)
  execute_process(COMMAND ${MAPNIK_CONFIG} ${ARGN}
                  OUTPUT_VARIABLE result OUTPUT_STRIP_TRAILING_WHITESPACE)
  string(REPLACE "\n" " " result "${result}")
  separate_arguments(result)
  set(${out} ${result} PARENT_SCOPE)
endfunction()

# Warn when the installed Mapnik differs in major.minor from the one this
# release targets (mapnik.version in ../gradle.properties).
execute_process(COMMAND ${MAPNIK_CONFIG} --version
                OUTPUT_VARIABLE INSTALLED_MAPNIK OUTPUT_STRIP_TRAILING_WHITESPACE)
file(STRINGS ${CMAKE_CURRENT_SOURCE_DIR}/../gradle.properties TARGET_LINE REGEX "^mapnik\\.version=")
string(REGEX REPLACE "^mapnik\\.version=" "" TARGET_MAPNIK "${TARGET_LINE}")
string(REGEX MATCH "^[0-9]+\\.[0-9]+" INSTALLED_MINOR "${INSTALLED_MAPNIK}")
string(REGEX MATCH "^[0-9]+\\.[0-9]+" TARGET_MINOR "${TARGET_MAPNIK}")
if(NOT INSTALLED_MINOR STREQUAL TARGET_MINOR)
  message(WARNING "Installed Mapnik ${INSTALLED_MAPNIK}, but this mapnik-java release targets ${TARGET_MAPNIK}. The build may fail or behave differently.")
endif()

mapnik_config(MAPNIK_CFLAGS --defines --includes --dep-includes)
mapnik_config(MAPNIK_LIBS   --libs --ldflags --dep-libs)

add_library(mapnik_c SHARED mapnik_c.cpp)
target_compile_options(mapnik_c PRIVATE ${MAPNIK_CFLAGS})
target_link_libraries(mapnik_c PRIVATE ${MAPNIK_LIBS})

# Drop lib next to where Maven expects it.
set_target_properties(mapnik_c PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../build/native)
