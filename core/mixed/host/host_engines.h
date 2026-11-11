// The three engines on the development host, behind the coordinator's Engines interface: librime
// and the LatinIME core linked in, Mozc through its line service. For spike S6's corpus run and
// its tests; the device answers the same questions with C functions the engine libraries export.

#ifndef KOTOMO_CORE_MIXED_HOST_HOST_ENGINES_H_
#define KOTOMO_CORE_MIXED_HOST_HOST_ENGINES_H_

#include <memory>
#include <string>

#include "engines.h"

namespace kotomo::mixed::host {

/// Names the files the host engines open.
struct Paths {
  std::string rime_shared;   // build/rime-prebuilt: the schema data with its compiled build/
  std::string rime_user;     // an empty writable directory; nothing is learned into it
  std::string english_dict;  // build/lexicon/english/main_en_us.dict
  std::string mozc_service;  // build/mozc/out/host/kotomo_mozc_service
  std::string mozc_data;     // build/mozc/out/host/mozc.data
  std::string mozc_profile;  // an empty writable directory
};

/// Opens the three engines.
/// Returns: nullptr with `error` naming the engine that did not open.
/// Side effects: starts librime (process-wide, one session, the schema that never learns, zh_hans
///   on as the app's default), maps the English dictionary and the spelling table, and starts the
///   Mozc service process.
std::unique_ptr<Engines> Open(const Paths& paths, std::string* error);

}  // namespace kotomo::mixed::host

#endif  // KOTOMO_CORE_MIXED_HOST_HOST_ENGINES_H_
