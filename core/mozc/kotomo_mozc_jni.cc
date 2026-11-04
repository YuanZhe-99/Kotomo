// JNI entry points of libkotomo_mozc.so for com.yuanzhe.kotomo.engine.Mozc.
//
// The library exports only these functions (exports.lds); Mozc, protobuf and
// abseil stay hidden inside it, so they cannot clash with librime's copies.
// Results cross the boundary as a flat string array, decoded by Mozc.kt:
//   per segment: reading, "1" or "0" (pinned), candidate count n,
//   then n pairs of (value, reading).

#include <jni.h>

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "base/util.h"
#include "kotomo/kotomo_mozc.h"

namespace kotomo::mozc {
namespace {

/// Converts a Java string to UTF-8 through UTF-16, so supplementary
/// characters survive (JNI's "modified UTF-8" would mangle them).
/// Returns: nullopt for a null reference or an unpaired surrogate.
std::optional<std::string> ToUtf8(JNIEnv* env, jstring text) {
  if (text == nullptr) {
    return std::nullopt;
  }
  const jsize length = env->GetStringLength(text);
  std::u16string units(static_cast<size_t>(length), u'\0');
  env->GetStringRegion(text, 0, length, reinterpret_cast<jchar*>(units.data()));
  std::string out;
  out.reserve(units.size() * 3);
  for (size_t i = 0; i < units.size(); ++i) {
    char32_t c = units[i];
    if (c >= 0xD800 && c <= 0xDBFF && i + 1 < units.size() &&
        units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF) {
      c = 0x10000 + ((c - 0xD800) << 10) + (units[++i] - 0xDC00);
    } else if (c >= 0xD800 && c <= 0xDFFF) {
      return std::nullopt;
    }
    ::mozc::Util::CodepointToUtf8Append(c, &out);
  }
  return out;
}

/// Converts UTF-8 from Mozc to a Java string through UTF-16.
jstring ToJava(JNIEnv* env, const std::string& text) {
  std::u16string units;
  units.reserve(text.size());
  for (char32_t c : ::mozc::Util::Utf8ToUtf32(text)) {
    if (c >= 0x10000) {
      c -= 0x10000;
      units += static_cast<char16_t>(0xD800 + (c >> 10));
      units += static_cast<char16_t>(0xDC00 + (c & 0x3FF));
    } else {
      units += static_cast<char16_t>(c);
    }
  }
  return env->NewString(reinterpret_cast<const jchar*>(units.data()),
                        static_cast<jsize>(units.size()));
}

/// Flattens a conversion into the string array Mozc.kt decodes.
/// Returns: nullptr for no conversion (Kotlin sees null).
jobjectArray Flatten(JNIEnv* env, const std::optional<Conversion>& conversion) {
  if (!conversion) {
    return nullptr;
  }
  std::vector<std::string> fields;
  for (const Segment& segment : conversion->segments) {
    fields.push_back(segment.key);
    fields.push_back(segment.pinned ? "1" : "0");
    fields.push_back(std::to_string(segment.candidates.size()));
    for (const Candidate& candidate : segment.candidates) {
      fields.push_back(candidate.value);
      fields.push_back(candidate.key);
    }
  }
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray array = env->NewObjectArray(static_cast<jsize>(fields.size()),
                                           string_class, nullptr);
  if (array == nullptr) {
    return nullptr;  // OutOfMemoryError is pending
  }
  for (size_t i = 0; i < fields.size(); ++i) {
    jstring value = ToJava(env, fields[i]);
    env->SetObjectArrayElement(array, static_cast<jsize>(i), value);
    env->DeleteLocalRef(value);
  }
  return array;
}

MozcEngine* FromHandle(jlong handle) {
  return reinterpret_cast<MozcEngine*>(static_cast<intptr_t>(handle));
}

}  // namespace
}  // namespace kotomo::mozc

using kotomo::mozc::FromHandle;

extern "C" {

/// Opens Mozc's data file; returns a handle, or 0 on failure.
JNIEXPORT jlong JNICALL Java_com_yuanzhe_kotomo_engine_Mozc_nativeOpen(
    JNIEnv* env, jclass, jstring data_path, jstring profile_dir) {
  const auto data = kotomo::mozc::ToUtf8(env, data_path);
  const auto profile = kotomo::mozc::ToUtf8(env, profile_dir);
  if (!data || !profile) {
    return 0;
  }
  return static_cast<jlong>(reinterpret_cast<intptr_t>(
      kotomo::mozc::MozcEngine::Open(*data, *profile).release()));
}

/// Releases the engine behind a handle; 0 is ignored.
JNIEXPORT void JNICALL Java_com_yuanzhe_kotomo_engine_Mozc_nativeClose(
    JNIEnv*, jclass, jlong handle) {
  delete FromHandle(handle);
}

/// Returns the data set's version, or null for a 0 handle.
JNIEXPORT jstring JNICALL Java_com_yuanzhe_kotomo_engine_Mozc_nativeDataVersion(
    JNIEnv* env, jclass, jlong handle) {
  if (handle == 0) {
    return nullptr;
  }
  return kotomo::mozc::ToJava(env, FromHandle(handle)->DataVersion());
}

/// Converts typed romaji or kana through a Mozc session (MozcEngine::SessionConvert).
JNIEXPORT jobjectArray JNICALL
Java_com_yuanzhe_kotomo_engine_Mozc_nativeSessionConvert(JNIEnv* env, jclass,
                                                         jlong handle,
                                                         jstring typed,
                                                         jboolean incognito) {
  const auto text = kotomo::mozc::ToUtf8(env, typed);
  if (handle == 0 || !text) {
    return nullptr;
  }
  return kotomo::mozc::Flatten(
      env, FromHandle(handle)->SessionConvert(*text, incognito == JNI_TRUE));
}

/// Converts a reading with pinned spans (MozcEngine::PinnedConvert).
/// Inputs: parallel arrays; an empty surface leaves its span to Mozc.
JNIEXPORT jobjectArray JNICALL
Java_com_yuanzhe_kotomo_engine_Mozc_nativePinnedConvert(
    JNIEnv* env, jclass, jlong handle, jobjectArray readings,
    jobjectArray surfaces, jboolean incognito) {
  if (handle == 0 || readings == nullptr || surfaces == nullptr) {
    return nullptr;
  }
  const jsize count = env->GetArrayLength(readings);
  if (count != env->GetArrayLength(surfaces)) {
    return nullptr;
  }
  std::vector<kotomo::mozc::Span> spans;
  spans.reserve(static_cast<size_t>(count));
  for (jsize i = 0; i < count; ++i) {
    auto reading_ref =
        static_cast<jstring>(env->GetObjectArrayElement(readings, i));
    auto surface_ref =
        static_cast<jstring>(env->GetObjectArrayElement(surfaces, i));
    auto reading = kotomo::mozc::ToUtf8(env, reading_ref);
    auto surface = kotomo::mozc::ToUtf8(env, surface_ref);
    env->DeleteLocalRef(reading_ref);
    env->DeleteLocalRef(surface_ref);
    if (!reading || !surface) {
      return nullptr;
    }
    spans.push_back({.reading = *std::move(reading),
                     .surface = *std::move(surface)});
  }
  return kotomo::mozc::Flatten(
      env, FromHandle(handle)->PinnedConvert(spans, incognito == JNI_TRUE));
}

}  // extern "C"
