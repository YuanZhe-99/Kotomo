// JNI entry points of libkotomo_core.so for com.yuanzhe.kotomo.engine.Core: the app's one
// crossing per key (doc/en-us/engine-contract.md). A request's bytes come in, a snapshot's bytes
// go out; the session, the coordinator and the engines' tables stay on this side.
//
// Thread: every entry point runs on the engine thread. Privacy: nothing here logs.

#include <jni.h>

#include <memory>
#include <string>

#include "api_engines.h"
#include "cross_lexicon.h"
#include "request.h"
#include "session.h"

namespace {

using kotomo::mixed::CrossLexicon;
using kotomo::mixed::device::ApiEngines;

/// Holds what one open coordinator owns.
struct Core {
  std::unique_ptr<ApiEngines> engines;
  std::unique_ptr<CrossLexicon> lexicon;
  std::unique_ptr<kotomo::mixed::Session> session;
};

}  // namespace

extern "C" {

/// Opens a coordinator over the engines' function tables.
/// Inputs: the addresses Rime.api(), Mozc.api() and English.api() returned (0: no such engine)
///   with Mozc's and the English engine's handles; `lexicon_path` names the file that holds the
///   compiled cross-language lexicon at `offset` for `length` bytes, or is null for none.
/// Returns: a handle, or 0 when a table has another version than this library expects or the
///   range is negative. A lexicon that does not open leaves the coordinator without
///   cross-language words (nativeEntries then answers 0).
/// Notes: JNI boundary; the addresses come from Kotomo's own libraries in the same process.
JNIEXPORT jlong JNICALL Java_com_yuanzhe_kotomo_engine_Core_nativeOpen(
    JNIEnv* env, jclass, jlong rime_api, jlong mozc_api, jlong mozc_engine, jlong english_api,
    jlong english_engine, jstring lexicon_path, jlong offset, jlong length) {
  if (offset < 0 || length < 0) return 0;
  auto core = std::make_unique<Core>();
  core->engines = ApiEngines::Create({
      .rime = reinterpret_cast<const KotomoRimeApi*>(rime_api),
      .mozc = reinterpret_cast<const KotomoMozcApi*>(mozc_api),
      .mozc_engine = reinterpret_cast<void*>(mozc_engine),
      .english = reinterpret_cast<const KotomoEnglishApi*>(english_api),
      .english_engine = reinterpret_cast<void*>(english_engine),
  });
  if (!core->engines) return 0;
  if (lexicon_path != nullptr) {
    const char* chars = env->GetStringUTFChars(lexicon_path, nullptr);
    if (chars == nullptr) return 0;  // OutOfMemoryError is pending
    core->lexicon = CrossLexicon::Map(chars, static_cast<uint64_t>(offset),
                                      static_cast<uint64_t>(length));
    env->ReleaseStringUTFChars(lexicon_path, chars);
  }
  if (!core->lexicon) core->lexicon = CrossLexicon::Empty();
  core->session = std::make_unique<kotomo::mixed::Session>(*core->engines, *core->lexicon);
  return reinterpret_cast<jlong>(core.release());
}

/// Returns: the number of entries of the mapped cross-language lexicon; 0 without one.
JNIEXPORT jint JNICALL Java_com_yuanzhe_kotomo_engine_Core_nativeEntries(JNIEnv*, jclass,
                                                                         jlong handle) {
  const Core* core = reinterpret_cast<const Core*>(handle);
  return core == nullptr ? 0 : static_cast<jint>(core->lexicon->size());
}

/// Applies one request and returns the snapshot it leaves, both encoded as the contract says.
/// Returns: null for a closed handle, a null array or a request that does not decode.
JNIEXPORT jbyteArray JNICALL Java_com_yuanzhe_kotomo_engine_Core_nativeCall(
    JNIEnv* env, jclass, jlong handle, jbyteArray request) {
  Core* core = reinterpret_cast<Core*>(handle);
  if (core == nullptr || request == nullptr) return nullptr;
  std::string bytes(static_cast<size_t>(env->GetArrayLength(request)), '\0');
  env->GetByteArrayRegion(request, 0, static_cast<jsize>(bytes.size()),
                          reinterpret_cast<jbyte*>(bytes.data()));
  const auto decoded = kotomo::mixed::DecodeRequest(bytes);
  if (!decoded) return nullptr;
  const std::string snapshot = kotomo::mixed::Encode(core->session->Handle(*decoded));
  jbyteArray out = env->NewByteArray(static_cast<jsize>(snapshot.size()));
  if (out == nullptr) return nullptr;  // OutOfMemoryError is pending
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(snapshot.size()),
                          reinterpret_cast<const jbyte*>(snapshot.data()));
  return out;
}

/// Closes a coordinator; 0 is ignored. Side effects: unmaps the lexicon.
JNIEXPORT void JNICALL Java_com_yuanzhe_kotomo_engine_Core_nativeClose(JNIEnv*, jclass,
                                                                       jlong handle) {
  delete reinterpret_cast<Core*>(handle);
}

}  // extern "C"
