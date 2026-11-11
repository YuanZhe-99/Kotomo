# Builds Kotomo's mixed-input coordinator (PLAN.md §5) and its host tests for spike S6.
#
# Host only for now: the device build joins core/CMakeLists.txt when the coordinator is wired to the
# app (PLAN.md §0.3, Q7). The coordinator library itself links no engine.

cmake_minimum_required(VERSION 3.22)
project(kotomo_mixed CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
enable_testing()

add_library(kotomo_mixed STATIC
  coordinator.cc
  cross_lexicon.cc
  decode.cc
  finish_chinese.cc
  finish_japanese.cc
  input.cc
  lattice.cc
  propose_chinese.cc
  propose_cross.cc
  propose_english.cc
  propose_japanese.cc
  snapshot.cc)
target_include_directories(kotomo_mixed PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_options(kotomo_mixed PRIVATE -Wall -Wextra -Wno-missing-field-initializers)

# One executable per test file; each prints PASS/FAIL lines named after the spike or test ID.
foreach(name snapshot lattice cross_lexicon propose_chinese propose_japanese
    propose_english decode coordinator)
  add_executable(kotomo_mixed_${name}_test tests/${name}_test.cc)
  target_link_libraries(kotomo_mixed_${name}_test PRIVATE kotomo_mixed)
  add_test(NAME kotomo_mixed_${name}_test COMMAND kotomo_mixed_${name}_test)
endforeach()
