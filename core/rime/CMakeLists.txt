# Builds librime 1.17 and its dependencies from the submodules in third_party/ into one shared
# library, libkotomo_rime.so, whose only exports are the JNI entry points (jni/exports.map).
#
# Android (Gradle's externalNativeBuild, or the NDK toolchain file directly) builds the shared
# library. A host build (Linux) builds the same shared library for the conversion corpus, plus the
# prebuild tool and the tests in tests/.
#
# Upstream code is compiled as checked out: librime and OpenCC get their target definitions here
# (their own CMake files need find_package results and, for OpenCC's data, Python), while LevelDB,
# marisa-trie and yaml-cpp come in through add_subdirectory with their tests and tools off. Boost is
# headers only (librime uses no compiled Boost library), read from the release archive that
# scripts/fetch-boost.sh unpacks into .deps/.

cmake_minimum_required(VERSION 3.22)
project(kotomo_rime C CXX)

set(KOTOMO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." CACHE PATH "Kotomo repository root")
set(KOTOMO_BOOST_DIR "${KOTOMO_ROOT}/.deps/boost/boost_1_92_0" CACHE PATH "Boost release tree")
set(tp "${KOTOMO_ROOT}/third_party")

if(NOT EXISTS "${KOTOMO_BOOST_DIR}/boost/version.hpp")
  message(FATAL_ERROR "Boost headers not found in ${KOTOMO_BOOST_DIR}; run scripts/fetch-boost.sh")
endif()
if(NOT EXISTS "${tp}/librime/src/rime_api.h")
  message(FATAL_ERROR "third_party/librime is empty; run `git submodule update --init`")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(BUILD_SHARED_LIBS OFF)
# Let the normal variables below decide the subprojects' option() values.
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)

find_package(Threads REQUIRED)

# --- LevelDB, marisa-trie, yaml-cpp: their own CMake, library only -------------------------------

set(LEVELDB_BUILD_TESTS OFF)
set(LEVELDB_BUILD_BENCHMARKS OFF)
set(LEVELDB_INSTALL OFF)
add_subdirectory("${tp}/leveldb" leveldb EXCLUDE_FROM_ALL)

set(ENABLE_TOOLS OFF)
set(BUILD_TESTING OFF)
add_subdirectory("${tp}/marisa-trie" marisa EXCLUDE_FROM_ALL)

set(YAML_CPP_BUILD_TESTS OFF)
set(YAML_CPP_BUILD_TOOLS OFF)
set(YAML_CPP_BUILD_CONTRIB OFF)
set(YAML_CPP_INSTALL OFF)
set(YAML_CPP_FORMAT_SOURCE OFF)
set(YAML_BUILD_SHARED_LIBS OFF)
add_subdirectory("${tp}/yaml-cpp" yaml-cpp EXCLUDE_FROM_ALL)

# --- OpenCC: the library sources of OpenCC's src/CMakeLists.txt ----------------------------------

set(opencc_src "${tp}/opencc/src")
add_library(kotomo_opencc STATIC
  ${opencc_src}/BinaryDict.cpp
  ${opencc_src}/Config.cpp
  ${opencc_src}/Conversion.cpp
  ${opencc_src}/ConversionAmbiguities.cpp
  ${opencc_src}/ConversionCandidates.cpp
  ${opencc_src}/ConversionChain.cpp
  ${opencc_src}/Converter.cpp
  ${opencc_src}/DartsDict.cpp
  ${opencc_src}/Dict.cpp
  ${opencc_src}/DictConverter.cpp
  ${opencc_src}/DictEntry.cpp
  ${opencc_src}/DictGroup.cpp
  ${opencc_src}/Lexicon.cpp
  ${opencc_src}/MarisaDict.cpp
  ${opencc_src}/MaxMatchSegmentation.cpp
  ${opencc_src}/PhraseExtract.cpp
  ${opencc_src}/PipelineConverter.cpp
  ${opencc_src}/PluginSegmentation.cpp
  ${opencc_src}/PrefixMatch.cpp
  ${opencc_src}/ResourceProvider.cpp
  ${opencc_src}/Segmentation.cpp
  ${opencc_src}/SerializableDict.cpp
  ${opencc_src}/SerializedValues.cpp
  ${opencc_src}/SimpleConverter.cpp
  ${opencc_src}/SingleStageConverter.cpp
  ${opencc_src}/TextDict.cpp
  ${opencc_src}/UTF8StringSlice.cpp
  ${opencc_src}/UTF8Util.cpp)
# librime includes <opencc/Config.hpp>; OpenCC's headers live in src/, so mirror them as opencc/.
set(opencc_include "${CMAKE_CURRENT_BINARY_DIR}/opencc-include")
file(GLOB opencc_headers "${opencc_src}/*.hpp" "${opencc_src}/*.h")
file(COPY ${opencc_headers} DESTINATION "${opencc_include}/opencc")
include(GenerateExportHeader)
generate_export_header(kotomo_opencc
  BASE_NAME OPENCC
  EXPORT_MACRO_NAME OPENCC_EXPORT
  EXPORT_FILE_NAME "${opencc_include}/opencc/Opencc_Export.h"
  STATIC_DEFINE Opencc_BUILT_AS_STATIC)
target_include_directories(kotomo_opencc
  PUBLIC "${opencc_include}"
  PRIVATE "${opencc_include}/opencc"
          "${tp}/opencc/deps/rapidjson-1.1.0"
          "${tp}/opencc/deps/darts-clone-0.32h/include")
target_compile_definitions(kotomo_opencc
  PUBLIC Opencc_BUILT_AS_STATIC
  PRIVATE PKGDATADIR="" LOCALEDIR="" PACKAGE_NAME="opencc" OPENCC_SEGMENTATION_PLUGIN_DIR="")
target_link_libraries(kotomo_opencc PUBLIC marisa ${CMAKE_DL_LIBS})

# --- librime: the sources of librime's src/CMakeLists.txt, without plugins -----------------------

set(rime_src "${tp}/librime/src")
file(STRINGS "${tp}/librime/CMakeLists.txt" rime_version_line REGEX "^set\\(rime_version ")
string(REGEX MATCH "[0-9]+\\.[0-9]+\\.[0-9]+" rime_version "${rime_version_line}")
file(GLOB rime_sources CONFIGURE_DEPENDS
  "${rime_src}/*.cc"
  "${rime_src}/rime/*.cc"
  "${rime_src}/rime/algo/*.cc"
  "${rime_src}/rime/config/*.cc"
  "${rime_src}/rime/dict/*.cc"
  "${rime_src}/rime/gear/*.cc"
  "${rime_src}/rime/lever/*.cc")
add_library(kotomo_librime STATIC ${rime_sources})
# Logging stays off (no glog): librime's no_logging.h discards every message, so typed text can
# never reach a log. RIME_DATA_DIR and RIME_PLUGINS_DIR keep librime's defaults.
set(RIME_DATA_DIR "rime-data")
set(RIME_PLUGINS_DIR "rime-plugins")
configure_file("${rime_src}/rime/build_config.h.in"
               "${CMAKE_CURRENT_BINARY_DIR}/rime-include/rime/build_config.h")
target_include_directories(kotomo_librime SYSTEM PUBLIC
  "${CMAKE_CURRENT_BINARY_DIR}/rime-include"
  "${rime_src}"
  "${tp}/librime/include"
  "${KOTOMO_BOOST_DIR}")
target_compile_definitions(kotomo_librime
  PUBLIC RIME_VERSION="${rime_version}"
  PRIVATE RIME_EXTRA_MODULES= BOOST_DLL_USE_STD_FS)
target_link_libraries(kotomo_librime PUBLIC yaml-cpp leveldb marisa kotomo_opencc Threads::Threads)

# --- Kotomo's bridge -----------------------------------------------------------------------------

add_library(kotomo_rime_bridge STATIC jni/kotomo_rime_api.cc jni/rime_bridge.cc jni/rime_spellings.cc)
target_include_directories(kotomo_rime_bridge PUBLIC jni)
target_link_libraries(kotomo_rime_bridge PUBLIC kotomo_librime)

# The JNI library: the app's on Android; on the host, the conversion corpus (tests/conversion) loads
# it into a JVM through the app's own facade.
add_library(kotomo_rime SHARED jni/rime_jni.cc)
target_link_libraries(kotomo_rime PRIVATE kotomo_rime_bridge)
target_link_options(kotomo_rime PRIVATE
  "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni/exports.map"
  "-Wl,--exclude-libs,ALL"
  "-Wl,--gc-sections"
  "-Wl,-z,max-page-size=16384")
set_target_properties(kotomo_rime PROPERTIES LINK_DEPENDS
                      "${CMAKE_CURRENT_SOURCE_DIR}/jni/exports.map")

if(NOT ANDROID)
  find_package(JNI REQUIRED COMPONENTS JVM)
  target_include_directories(kotomo_rime PRIVATE ${JNI_INCLUDE_DIRS})
  # Host: the prebuild tool (scripts/prebuild-rime.sh) and the tests.
  add_executable(kotomo_rime_prebuild tools/prebuild.cc)
  target_link_libraries(kotomo_rime_prebuild PRIVATE kotomo_rime_bridge)
  add_executable(kotomo_rime_check tools/check.cc)
  target_link_libraries(kotomo_rime_check PRIVATE kotomo_rime_bridge)
  # The tests belong to this project's own build, not to one that adds it (core/mixed).
  if(PROJECT_IS_TOP_LEVEL)
    enable_testing()
    add_subdirectory(tests)
  endif()
endif()
