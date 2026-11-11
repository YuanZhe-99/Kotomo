// What english_jni.cpp and english_api.cc share: the engine behind a handle, and the exact lookup
// the coordinator asks for.

#ifndef KOTOMO_CORE_ENGLISH_JNI_ENGLISH_ENGINE_H_
#define KOTOMO_CORE_ENGLISH_JNI_ENGLISH_ENGINE_H_

#include <memory>
#include <string_view>

#include "dictionary/interface/dictionary_structure_with_buffer_policy.h"
#include "english_api.h"
#include "suggest/core/dictionary/dictionary.h"
#include "suggest/core/layout/proximity_info.h"
#include "suggest/core/session/dic_traverse_session.h"

namespace kotomo::english {

/// Holds one opened dictionary with the session and key geometry its suggestions use.
struct Engine {
    std::unique_ptr<latinime::Dictionary> dictionary;
    std::unique_ptr<latinime::ProximityInfo> proximity;
    latinime::DicTraverseSession *session = nullptr;

    ~Engine() { latinime::DicTraverseSession::releaseSessionInstance(session); }
};

/// Looks a word up exactly in a dictionary and passes its spellings to `sink`.
/// Inputs: `word` in lower-case ASCII letters, at most MAX_WORD_LENGTH; anything else finds
///   nothing.
/// Side effects: calls `sink` once per spelling the dictionary holds (as typed, capitalized, upper
///   case), most probable first; blacklisted entries and non-words are left out.
/// Notes: one trie walk per spelling, no scan and no suggestion search; the host's coordinator
///   tests call it on a dictionary opened without a JVM.
void Lookup(const latinime::DictionaryStructureWithBufferPolicy *dictionary, std::string_view word,
        void *context, KotomoEnglishFormSink sink);

} // namespace kotomo::english

#endif // KOTOMO_CORE_ENGLISH_JNI_ENGLISH_ENGINE_H_
