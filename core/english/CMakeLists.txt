# Builds libkotomo_english.so: the LatinIME suggestion core from HeliBoard's maintained copy
# (third_party/heliboard, unmodified) plus Kotomo's JNI bridge (jni/english_jni.cpp).
#
# Android: configured by the app's Gradle externalNativeBuild with the NDK toolchain
# (arm64-v8a, x86_64; ANDROID_STL c++_static). Host (Linux): the same library linked against the
# host JDK's jni.h, for the host test in tests/.
# Notes: the source list follows the upstream NativeFileList.mk (LATIN_IME_CORE_SRC_FILES and the
# v402 backward-compatibility files); LatinIME's own JNI files are left out on purpose.

cmake_minimum_required(VERSION 3.22.1)
project(kotomo_english CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LATINIME_JNI ${CMAKE_CURRENT_LIST_DIR}/../../third_party/heliboard/app/src/main/jni)
set(LATINIME_SRC ${LATINIME_JNI}/src)
if(NOT EXISTS ${LATINIME_SRC}/defines.h)
    message(FATAL_ERROR "third_party/heliboard is missing: run git submodule update --init")
endif()

set(LATINIME_CORE_FILES
    dictionary/header/header_policy.cpp
    dictionary/header/header_read_write_utils.cpp
    dictionary/property/ngram_context.cpp
    dictionary/structure/dictionary_structure_with_buffer_policy_factory.cpp
    dictionary/structure/pt_common/bigram/bigram_list_read_write_utils.cpp
    dictionary/structure/pt_common/dynamic_pt_gc_event_listeners.cpp
    dictionary/structure/pt_common/dynamic_pt_reading_helper.cpp
    dictionary/structure/pt_common/dynamic_pt_reading_utils.cpp
    dictionary/structure/pt_common/dynamic_pt_updating_helper.cpp
    dictionary/structure/pt_common/dynamic_pt_writing_utils.cpp
    dictionary/structure/pt_common/patricia_trie_reading_utils.cpp
    dictionary/structure/pt_common/shortcut/shortcut_list_reading_utils.cpp
    dictionary/structure/v2/patricia_trie_policy.cpp
    dictionary/structure/v2/ver2_patricia_trie_node_reader.cpp
    dictionary/structure/v2/ver2_pt_node_array_reader.cpp
    dictionary/structure/v4/ver4_dict_buffers.cpp
    dictionary/structure/v4/ver4_dict_constants.cpp
    dictionary/structure/v4/ver4_patricia_trie_node_reader.cpp
    dictionary/structure/v4/ver4_patricia_trie_node_writer.cpp
    dictionary/structure/v4/ver4_patricia_trie_policy.cpp
    dictionary/structure/v4/ver4_patricia_trie_reading_utils.cpp
    dictionary/structure/v4/ver4_patricia_trie_writing_helper.cpp
    dictionary/structure/v4/ver4_pt_node_array_reader.cpp
    dictionary/structure/v4/content/dynamic_language_model_probability_utils.cpp
    dictionary/structure/v4/content/language_model_dict_content.cpp
    dictionary/structure/v4/content/language_model_dict_content_global_counters.cpp
    dictionary/structure/v4/content/shortcut_dict_content.cpp
    dictionary/structure/v4/content/sparse_table_dict_content.cpp
    dictionary/structure/v4/content/terminal_position_lookup_table.cpp
    dictionary/utils/buffer_with_extendable_buffer.cpp
    dictionary/utils/byte_array_utils.cpp
    dictionary/utils/dict_file_writing_utils.cpp
    dictionary/utils/file_utils.cpp
    dictionary/utils/forgetting_curve_utils.cpp
    dictionary/utils/format_utils.cpp
    dictionary/utils/mmapped_buffer.cpp
    dictionary/utils/multi_bigram_map.cpp
    dictionary/utils/probability_utils.cpp
    dictionary/utils/sparse_table.cpp
    dictionary/utils/trie_map.cpp
    suggest/core/suggest.cpp
    suggest/core/dicnode/dic_node.cpp
    suggest/core/dicnode/dic_node_utils.cpp
    suggest/core/dicnode/dic_nodes_cache.cpp
    suggest/core/dictionary/dictionary.cpp
    suggest/core/dictionary/dictionary_utils.cpp
    suggest/core/dictionary/digraph_utils.cpp
    suggest/core/dictionary/error_type_utils.cpp
    suggest/core/layout/additional_proximity_chars.cpp
    suggest/core/layout/proximity_info.cpp
    suggest/core/layout/proximity_info_params.cpp
    suggest/core/layout/proximity_info_state.cpp
    suggest/core/layout/proximity_info_state_utils.cpp
    suggest/core/policy/weighting.cpp
    suggest/core/session/dic_traverse_session.cpp
    suggest/core/result/suggestion_results.cpp
    suggest/core/result/suggestions_output_utils.cpp
    suggest/policyimpl/gesture/gesture_suggest_policy_factory.cpp
    suggest/policyimpl/typing/scoring_params.cpp
    suggest/policyimpl/typing/typing_scoring.cpp
    suggest/policyimpl/typing/typing_suggest_policy.cpp
    suggest/policyimpl/typing/typing_traversal.cpp
    suggest/policyimpl/typing/typing_weighting.cpp
    utils/autocorrection_threshold_utils.cpp
    utils/char_utils.cpp
    utils/jni_data_utils.cpp
    utils/log_utils.cpp
    utils/time_keeper.cpp
    dictionary/structure/backward/v402/ver4_dict_buffers.cpp
    dictionary/structure/backward/v402/ver4_dict_constants.cpp
    dictionary/structure/backward/v402/ver4_patricia_trie_node_reader.cpp
    dictionary/structure/backward/v402/ver4_patricia_trie_node_writer.cpp
    dictionary/structure/backward/v402/ver4_patricia_trie_policy.cpp
    dictionary/structure/backward/v402/ver4_patricia_trie_reading_utils.cpp
    dictionary/structure/backward/v402/ver4_patricia_trie_writing_helper.cpp
    dictionary/structure/backward/v402/ver4_pt_node_array_reader.cpp
    dictionary/structure/backward/v402/content/bigram_dict_content.cpp
    dictionary/structure/backward/v402/content/probability_dict_content.cpp
    dictionary/structure/backward/v402/content/shortcut_dict_content.cpp
    dictionary/structure/backward/v402/content/sparse_table_dict_content.cpp
    dictionary/structure/backward/v402/content/terminal_position_lookup_table.cpp
    dictionary/structure/backward/v402/bigram/ver4_bigram_list_policy.cpp
)
list(TRANSFORM LATINIME_CORE_FILES PREPEND ${LATINIME_SRC}/)

add_library(kotomo_english SHARED ${LATINIME_CORE_FILES} jni/english_jni.cpp)
target_include_directories(kotomo_english PRIVATE ${LATINIME_SRC})
if(NOT ANDROID)
    find_package(JNI REQUIRED COMPONENTS JVM)
    target_include_directories(kotomo_english PRIVATE ${JNI_INCLUDE_DIRS})
endif()

# Upstream warnings stay off: the upstream code keeps its own style and is never edited.
set_source_files_properties(${LATINIME_CORE_FILES} PROPERTIES COMPILE_OPTIONS "-w")
target_compile_options(kotomo_english PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden
    -ffunction-sections -fdata-sections)
# Only the Java_com_yuanzhe_kotomo_engine_EnglishJni_* entry points are exported; 16 KB pages.
target_link_options(kotomo_english PRIVATE
    -Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/jni/exports.map
    -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--build-id=none)
set_target_properties(kotomo_english PROPERTIES LINK_DEPENDS
    ${CMAKE_CURRENT_LIST_DIR}/jni/exports.map)
