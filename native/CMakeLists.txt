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

mapnik_config(MAPNIK_CFLAGS --defines --includes --dep-includes)
mapnik_config(MAPNIK_LIBS   --libs --ldflags --dep-libs)

add_library(mapnik_c SHARED mapnik_c.cpp)
target_compile_options(mapnik_c PRIVATE ${MAPNIK_CFLAGS})
target_link_libraries(mapnik_c PRIVATE ${MAPNIK_LIBS})

# Drop lib next to where Maven expects it.
set_target_properties(mapnik_c PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../build/native)
