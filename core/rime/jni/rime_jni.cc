// JNI entry points of libkotomo_rime.so for com.yuanzhe.kotomo.engine.Rime.
//
// Strings cross as UTF-16 (NewString / GetStringChars), not JNI's modified UTF-8, so characters
// outside the BMP (CJK Extension B and later, emoji) survive in both directions.

#include <jni.h>

#include <iterator>
#include <string>

#include <utf8.h>

#include "rime_bridge.h"

namespace rb = kotomo::rime_bridge;

namespace {

/// Converts a Java string to UTF-8; null becomes empty and unpaired surrogates become U+FFFD.
std::string ToUtf8(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const jsize length = env->GetStringLength(value);
  const jchar* chars = env->GetStringChars(value, nullptr);
  if (chars == nullptr) return {};
  std::u16string utf16(reinterpret_cast<const char16_t*>(chars), static_cast<size_t>(length));
  env->ReleaseStringChars(value, chars);
  // Encode without checks, then repair: a malformed string from another app never throws.
  std::string raw;
  utf8::unchecked::utf16to8(utf16.begin(), utf16.end(), std::back_inserter(raw));
  std::string utf8;
  utf8::replace_invalid(raw.begin(), raw.end(), std::back_inserter(utf8));
  return utf8;
}

/// Converts UTF-8 from librime to a Java string; invalid bytes become U+FFFD.
jstring ToJava(JNIEnv* env, const std::string& value) {
  std::string valid;
  utf8::replace_invalid(value.begin(), value.end(), std::back_inserter(valid));
  std::u16string utf16;
  utf8::unchecked::utf8to16(valid.begin(), valid.end(), std::back_inserter(utf16));
  return env->NewString(reinterpret_cast<const jchar*>(utf16.data()),
                        static_cast<jsize>(utf16.size()));
}

}  // namespace

extern "C" {

/// Starts librime on the given directories; see rime_bridge::Initialize.
JNIEXPORT jboolean JNICALL Java_com_yuanzhe_kotomo_engine_Rime_initialize(
    JNIEnv* env, jobject, jstring shared_data_dir, jstring user_data_dir) {
  return rb::Initialize(ToUtf8(env, shared_data_dir), ToUtf8(env, user_data_dir));
}

/// Compiles the schemas; see rime_bridge::Deploy.
JNIEXPORT jboolean JNICALL Java_com_yuanzhe_kotomo_engine_Rime_deploy(JNIEnv*, jobject) {
  return rb::Deploy();
}

/// Names the deploy tasks that failed; see rime_bridge::DeployFailures.
JNIEXPORT jstring JNICALL Java_com_yuanzhe_kotomo_engine_Rime_deployFailures(JNIEnv* env, jobject) {
  return ToJava(env, rb::DeployFailures());
}

/// Replaces the raw input; see rime_bridge::SetInput.
JNIEXPORT jboolean JNICALL Java_com_yuanzhe_kotomo_engine_Rime_setInput(JNIEnv* env, jobject,
                                                                         jstring input) {
  return rb::SetInput(ToUtf8(env, input));
}

/// Sends one X11 keysym with modifier bits; see rime_bridge::ProcessKey.
JNIEXPORT jboolean JNICALL Java_com_yuanzhe_kotomo_engine_Rime_processKey(JNIEnv*, jobject,
                                                                           jint keycode,
                                                                           jint mask) {
  return rb::ProcessKey(keycode, mask);
}

/// Returns the preedit text; see rime_bridge::Composition.
JNIEXPORT jstring JNICALL Java_com_yuanzhe_kotomo_engine_Rime_getComposition(JNIEnv* env,
                                                                             jobject) {
  return ToJava(env, rb::Composition());
}

/// Returns up to `limit` candidates flattened as [text0, comment0, text1, comment1, …].
JNIEXPORT jobjectArray JNICALL Java_com_yuanzhe_kotomo_engine_Rime_nativeCandidates(
    JNIEnv* env, jobject, jint limit) {
  const auto candidates = rb::Candidates(limit > 0 ? static_cast<size_t>(limit) : 0);
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray result =
      env->NewObjectArray(static_cast<jsize>(candidates.size() * 2), string_class, nullptr);
  if (result == nullptr) return nullptr;  // OutOfMemoryError is pending
  jsize i = 0;
  for (const auto& candidate : candidates) {
    for (const std::string* value : {&candidate.text, &candidate.comment}) {
      jstring item = ToJava(env, *value);
      env->SetObjectArrayElement(result, i++, item);
      env->DeleteLocalRef(item);
    }
  }
  return result;
}

/// Selects a candidate by absolute index; see rime_bridge::SelectCandidate.
JNIEXPORT jboolean JNICALL Java_com_yuanzhe_kotomo_engine_Rime_selectCandidate(JNIEnv*, jobject,
                                                                                jint index) {
  return index >= 0 && rb::SelectCandidate(static_cast<size_t>(index));
}

/// Returns the committed text, or null when nothing was committed; see rime_bridge::TakeCommit.
JNIEXPORT jstring JNICALL Java_com_yuanzhe_kotomo_engine_Rime_getCommit(JNIEnv* env, jobject) {
  const std::string commit = rb::TakeCommit();
  return commit.empty() ? nullptr : ToJava(env, commit);
}

/// Discards the composition; see rime_bridge::Clear.
JNIEXPORT void JNICALL Java_com_yuanzhe_kotomo_engine_Rime_clear(JNIEnv*, jobject) {
  rb::Clear();
}

/// Sets a schema switch or option; see rime_bridge::SetOption.
JNIEXPORT void JNICALL Java_com_yuanzhe_kotomo_engine_Rime_setOption(JNIEnv* env, jobject,
                                                                     jstring name,
                                                                     jboolean value) {
  rb::SetOption(ToUtf8(env, name), value == JNI_TRUE);
}

/// Turns learning on or off; see rime_bridge::SetLearning.
JNIEXPORT jboolean JNICALL Java_com_yuanzhe_kotomo_engine_Rime_setLearning(JNIEnv*, jobject,
                                                                            jboolean enabled) {
  return rb::SetLearning(enabled == JNI_TRUE);
}

/// Stops librime; see rime_bridge::Shutdown.
JNIEXPORT void JNICALL Java_com_yuanzhe_kotomo_engine_Rime_shutdown(JNIEnv*, jobject) {
  rb::Shutdown();
}

}  // extern "C"
