# Builds Kotomo's mixed-input coordinator (PLAN.md §5): libkotomo_core.so, the app's one native
# facade (doc/en-us/engine-contract.md), for Android through core/CMakeLists.txt and for the host
# JVM that runs the conversion corpus; and, when this is the top-level project, the host tests,
# the lexicon compiler and the corpus runner. The coordinator links no engine.

cmake_minimum_required(VERSION 3.22)
project(kotomo_mixed CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

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
  request.cc
  session.cc
  snapshot.cc)
target_include_directories(kotomo_mixed PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_options(kotomo_mixed PRIVATE -Wall -Wextra -Wno-missing-field-initializers)

# The device side: the engines through the function tables their libraries hand over, and the
# JNI entry points. Only the tables' C headers are shared with the engine libraries.
add_library(kotomo_core SHARED device/api_engines.cc jni/core_jni.cc)
target_include_directories(kotomo_core PRIVATE device ../rime/jni ../english/jni ../mozc)
target_link_libraries(kotomo_core PRIVATE kotomo_mixed)
target_compile_options(kotomo_core PRIVATE -Wall -Wextra -Wno-missing-field-initializers
  -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
target_link_options(kotomo_core PRIVATE
  "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni/exports.map"
  "-Wl,--exclude-libs,ALL"
  "-Wl,--gc-sections"
  "-Wl,-z,max-page-size=16384")
set_target_properties(kotomo_core PROPERTIES LINK_DEPENDS
                      "${CMAKE_CURRENT_SOURCE_DIR}/jni/exports.map")
if(NOT ANDROID)
  find_package(JNI REQUIRED COMPONENTS JVM)
  target_include_directories(kotomo_core PRIVATE ${JNI_INCLUDE_DIRS})
endif()

if(NOT PROJECT_IS_TOP_LEVEL)
  return()
endif()
enable_testing()

# The lexicon compiler: for the build tool and the tests, never part of the device library.
add_library(kotomo_mixed_build STATIC cross_lexicon_build.cc)
target_link_libraries(kotomo_mixed_build PUBLIC kotomo_mixed)
target_compile_options(kotomo_mixed_build PRIVATE -Wall -Wextra)
add_executable(kotomo_lexicon_compile tools/compile_lexicon.cc)
target_link_libraries(kotomo_lexicon_compile PRIVATE kotomo_mixed_build)

# One executable per test file; each prints PASS/FAIL lines named after the spike or test ID.
foreach(name snapshot request session lattice cross_lexicon propose_chinese propose_japanese
    propose_english decode coordinator)
  add_executable(kotomo_mixed_${name}_test tests/${name}_test.cc)
  target_link_libraries(kotomo_mixed_${name}_test PRIVATE kotomo_mixed_build)
  add_test(NAME kotomo_mixed_${name}_test COMMAND kotomo_mixed_${name}_test)
endforeach()

# The host engines and the corpus runner: librime and the LatinIME core linked in, Mozc through
# the line service scripts/build-mozc.sh builds. Their own projects build the two libraries.
set(KOTOMO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." CACHE PATH "Kotomo repository root")
add_subdirectory(../rime rime EXCLUDE_FROM_ALL)
add_subdirectory(../english english EXCLUDE_FROM_ALL)

add_library(kotomo_mixed_host STATIC host/host_engines.cc host/mozc_client.cc)
target_include_directories(kotomo_mixed_host PUBLIC host)
target_link_libraries(kotomo_mixed_host PUBLIC kotomo_mixed
                      PRIVATE kotomo_rime_bridge kotomo_english_api)

add_executable(kotomo_mixed_corpus host/corpus_main.cc)
target_link_libraries(kotomo_mixed_corpus PRIVATE kotomo_mixed_host)
