// Kotomo's JNI bridge to the LatinIME suggestion core (third_party/heliboard, unmodified).
// It replaces LatinIME's own JNI layer (com_android_inputmethod_*.cpp), which registers on the
// upstream Java classes; Kotomo's natives are bound to com.yuanzhe.kotomo.engine.EnglishJni.
//
// Thread: every entry point runs on the engine thread; the core keeps per-call state in a
// process-wide clock (TimeKeeper) and in the session, so nothing here is called concurrently.
// Privacy: nothing in this file logs; the core's own logging is compiled out (no FLAG_DBG).

#include <jni.h>
#include <sys/stat.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "defines.h"
#include "english_api.h"
#include "english_engine.h"
#include "dictionary/property/ngram_context.h"
#include "dictionary/structure/dictionary_structure_with_buffer_policy_factory.h"
#include "suggest/core/dictionary/dictionary.h"
#include "suggest/core/layout/proximity_info.h"
#include "suggest/core/result/suggestion_results.h"
#include "suggest/core/session/dic_traverse_session.h"
#include "suggest/core/suggest_options.h"

namespace {

using kotomo::english::Engine;
using latinime::DicTraverseSession;
using latinime::Dictionary;
using latinime::DictionaryStructureWithBufferPolicyFactory;
using latinime::NgramContext;
using latinime::ProximityInfo;
using latinime::SuggestionResults;
using latinime::SuggestOptions;

/// Names the built-in QWERTY geometry that touch coordinates are expressed in.
/// Notes: keys are KEY_WIDTH × KEY_HEIGHT units; the app scales its touch points into this
/// KEYBOARD_WIDTH × KEYBOARD_HEIGHT space (EnglishJni documents the same numbers).
constexpr int KEY_WIDTH = 100;
constexpr int KEY_HEIGHT = 150;
constexpr int KEYBOARD_WIDTH = 10 * KEY_WIDTH;
constexpr int KEYBOARD_HEIGHT = 4 * KEY_HEIGHT;
// LatinIME's defaults: config_keyboard_grid_width/height and ProximityInfo.SEARCH_DISTANCE.
constexpr int GRID_WIDTH = 32;
constexpr int GRID_HEIGHT = 16;
constexpr float SEARCH_DISTANCE = 1.2f;

/// Describes one key of the built-in layout as a rectangle and the code point it types.
struct Key {
    int x, y, width, height, code;

    /// Returns the squared distance from (px, py) to the key's rectangle, 0 inside it.
    /// Notes: the same measure as LatinIME's Key.squaredDistanceToEdge.
    int squaredDistanceToEdge(int px, int py) const {
        const int edgeX = px < x ? x : std::min(px, x + width);
        const int edgeY = py < y ? y : std::min(py, y + height);
        const int dx = px - edgeX;
        const int dy = py - edgeY;
        return dx * dx + dy * dy;
    }
};

/// Lays out the lower-case QWERTY letters in three staggered rows and the space bar below them.
std::vector<Key> qwertyKeys() {
    static const char *const rows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    static const int rowOffsets[] = {0, KEY_WIDTH / 2, KEY_WIDTH * 3 / 2};
    std::vector<Key> keys;
    for (int row = 0; row < 3; ++row) {
        int x = rowOffsets[row];
        for (const char *c = rows[row]; *c; ++c, x += KEY_WIDTH) {
            keys.push_back({x, row * KEY_HEIGHT, KEY_WIDTH, KEY_HEIGHT, *c});
        }
    }
    keys.push_back({KEY_WIDTH * 5 / 2, 3 * KEY_HEIGHT, KEY_WIDTH * 5, KEY_HEIGHT, ' '});
    return keys;
}

/// Copies an int vector into a new Java int array.
/// Returns: a local reference the caller passes on to the core.
jintArray toJavaArray(JNIEnv *env, const std::vector<int> &values) {
    const jintArray array = env->NewIntArray(static_cast<jsize>(values.size()));
    env->SetIntArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    return array;
}

/// Builds the core's key-proximity model for the built-in QWERTY layout.
/// Returns: the model, or nullptr when the JVM ran out of memory for the temporary arrays.
/// Notes: follows LatinIME's ProximityInfo.computeNearestNeighbors: each grid cell lists up to
/// MAX_PROXIMITY_CHARS_SIZE keys whose edge lies within 1.2 key widths of the cell centre. No
/// touch-position correction (sweet spots) is supplied.
std::unique_ptr<ProximityInfo> newQwertyProximity(JNIEnv *env) {
    const std::vector<Key> keys = qwertyKeys();
    const int cellWidth = (KEYBOARD_WIDTH + GRID_WIDTH - 1) / GRID_WIDTH;
    const int cellHeight = (KEYBOARD_HEIGHT + GRID_HEIGHT - 1) / GRID_HEIGHT;
    const int threshold = static_cast<int>(KEY_WIDTH * SEARCH_DISTANCE);
    std::vector<int> proximityChars(GRID_WIDTH * GRID_HEIGHT * MAX_PROXIMITY_CHARS_SIZE,
            NOT_A_CODE_POINT);
    for (int cell = 0; cell < GRID_WIDTH * GRID_HEIGHT; ++cell) {
        const int centerX = (cell % GRID_WIDTH) * cellWidth + cellWidth / 2;
        const int centerY = (cell / GRID_WIDTH) * cellHeight + cellHeight / 2;
        int count = 0;
        for (const Key &key : keys) {
            if (count < MAX_PROXIMITY_CHARS_SIZE
                    && key.squaredDistanceToEdge(centerX, centerY) < threshold * threshold) {
                proximityChars[cell * MAX_PROXIMITY_CHARS_SIZE + count++] = key.code;
            }
        }
    }
    std::vector<int> xs, ys, widths, heights, codes;
    for (const Key &key : keys) {
        xs.push_back(key.x);
        ys.push_back(key.y);
        widths.push_back(key.width);
        heights.push_back(key.height);
        codes.push_back(key.code);
    }
    const jintArray jProximity = toJavaArray(env, proximityChars);
    const jintArray jXs = toJavaArray(env, xs);
    const jintArray jYs = toJavaArray(env, ys);
    const jintArray jWidths = toJavaArray(env, widths);
    const jintArray jHeights = toJavaArray(env, heights);
    const jintArray jCodes = toJavaArray(env, codes);
    if (env->ExceptionCheck()) return nullptr;
    std::unique_ptr<ProximityInfo> info(new ProximityInfo(env, KEYBOARD_WIDTH, KEYBOARD_HEIGHT,
            GRID_WIDTH, GRID_HEIGHT, KEY_WIDTH, KEY_HEIGHT, jProximity,
            static_cast<int>(keys.size()), jXs, jYs, jWidths, jHeights, jCodes,
            nullptr /* sweetSpotCenterXs */, nullptr /* sweetSpotCenterYs */,
            nullptr /* sweetSpotRadii */));
    for (jintArray a : {jProximity, jXs, jYs, jWidths, jHeights, jCodes}) env->DeleteLocalRef(a);
    return info;
}

/// Decodes a Java string into Unicode code points.
/// Returns: the code points; empty for a null string.
std::vector<int> codePoints(JNIEnv *env, jstring text) {
    std::vector<int> out;
    if (!text) return out;
    const jsize length = env->GetStringLength(text);
    std::vector<jchar> units(static_cast<size_t>(length));
    env->GetStringRegion(text, 0, length, units.data());
    for (jsize i = 0; i < length; ++i) {
        const int unit = units[i];
        if (unit >= 0xD800 && unit < 0xDC00 && i + 1 < length
                && units[i + 1] >= 0xDC00 && units[i + 1] < 0xE000) {
            out.push_back(0x10000 + ((unit - 0xD800) << 10) + (units[++i] - 0xDC00));
        } else {
            out.push_back(unit);
        }
    }
    return out;
}

/// Encodes code points as a new Java string.
/// Returns: a local reference, or nullptr when the JVM is out of memory.
jstring newString(JNIEnv *env, const int *points, int count) {
    std::vector<jchar> units;
    for (int i = 0; i < count; ++i) {
        const int cp = points[i];
        if (cp >= 0x10000) {
            units.push_back(static_cast<jchar>(0xD800 + ((cp - 0x10000) >> 10)));
            units.push_back(static_cast<jchar>(0xDC00 + ((cp - 0x10000) & 0x3FF)));
        } else {
            units.push_back(static_cast<jchar>(cp));
        }
    }
    return env->NewString(units.data(), static_cast<jsize>(units.size()));
}

/// Reads touch coordinates for the typed code points.
/// Returns: one coordinate per code point; NOT_A_COORDINATE everywhere when the array is null or
/// its length differs, so the core falls back to each key's centre.
std::vector<int> coordinates(JNIEnv *env, jintArray array, size_t count) {
    std::vector<int> out(count, NOT_A_COORDINATE);
    if (array && static_cast<size_t>(env->GetArrayLength(array)) == count) {
        env->GetIntArrayRegion(array, 0, static_cast<jsize>(count), out.data());
    }
    return out;
}

} // namespace

extern "C" {

/// Opens a LatinIME binary dictionary and prepares a typing session for it.
/// Inputs: path to a file holding the dictionary at byte offset `offset` for `length` bytes
/// (length < 0 means to the end of the file), so an uncompressed APK asset can be read in place.
/// Returns: an engine handle, or 0 when the file is missing or not a readable dictionary.
/// Side effects: memory-maps the dictionary read-only; allocates the session caches.
/// Notes: JNI boundary — the path and range are validated here.
JNIEXPORT jlong JNICALL Java_com_yuanzhe_kotomo_engine_EnglishJni_open(
        JNIEnv *env, jclass, jstring path, jlong offset, jlong length) {
    if (!path || offset < 0) return 0;
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    const std::string file(chars);
    env->ReleaseStringUTFChars(path, chars);
    struct stat info {};
    if (stat(file.c_str(), &info) != 0 || offset > info.st_size) return 0;
    const jlong size = length < 0 ? info.st_size - offset : length;
    if (size <= 0 || offset + size > info.st_size || offset + size > INT32_MAX) return 0;
    auto policy = DictionaryStructureWithBufferPolicyFactory::newPolicyForExistingDictFile(
            file.c_str(), static_cast<int>(offset), static_cast<int>(size),
            false /* isUpdatable */);
    if (!policy) return 0;
    auto engine = std::make_unique<Engine>();
    engine->dictionary.reset(new Dictionary(env, std::move(policy)));
    engine->proximity = newQwertyProximity(env);
    if (!engine->proximity) return 0;
    engine->session = static_cast<DicTraverseSession *>(
            DicTraverseSession::getSessionInstance(env, nullptr /* locale, unused */, size));
    return reinterpret_cast<jlong>(engine.release());
}

/// Suggests words for what was typed, or predicts the next word when nothing was typed yet.
/// Inputs: typed — the word so far (at most MAX_WORD_LENGTH code points); xs, ys — optional touch
/// points in the built-in layout's space, one per code point; previous — the word before it, or
/// null; outScores — receives one score per returned word (length ≥ MAX_RESULTS).
/// Returns: up to MAX_RESULTS words, best first; null for a closed handle or invalid input.
/// Notes: offensive words are blocked; no gesture input; the core's suggestion order is by score,
/// ties broken by shorter word first.
JNIEXPORT jobjectArray JNICALL Java_com_yuanzhe_kotomo_engine_EnglishJni_suggest(
        JNIEnv *env, jclass, jlong handle, jstring typed, jintArray xs, jintArray ys,
        jstring previous, jintArray outScores) {
    Engine *const engine = reinterpret_cast<Engine *>(handle);
    if (!engine || !outScores || env->GetArrayLength(outScores) < MAX_RESULTS) return nullptr;
    std::vector<int> input = codePoints(env, typed);
    const std::vector<int> prev = codePoints(env, previous);
    if (input.size() > MAX_WORD_LENGTH || prev.size() > MAX_WORD_LENGTH) return nullptr;
    const int inputSize = static_cast<int>(input.size());
    std::vector<int> x = coordinates(env, xs, input.size());
    std::vector<int> y = coordinates(env, ys, input.size());
    std::vector<int> times(input.size(), 0);
    std::vector<int> pointerIds(input.size(), 0);
    // SuggestOptions: not gesture, no full edit distance, block offensive words, no space-aware
    // gesture, locale weight 1.0 (in thousandths).
    const int options[] = {0, 0, 1, 0, 1000};
    const SuggestOptions suggestOptions(options, NELEMS(options));
    const NgramContext ngram = prev.empty() ? NgramContext()
            : NgramContext(prev.data(), static_cast<int>(prev.size()),
                      false /* isBeginningOfSentence */);
    SuggestionResults results(MAX_RESULTS);
    if (inputSize > 0) {
        engine->dictionary->getSuggestions(engine->proximity.get(), engine->session, x.data(),
                y.data(), times.data(), pointerIds.data(), input.data(), inputSize, &ngram,
                &suggestOptions, NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL, &results);
    } else {
        engine->dictionary->getPredictions(&ngram, &results);
    }

    // The core writes its results into Java arrays only; read them back from there.
    const jintArray jCount = env->NewIntArray(1);
    const jintArray jPoints = env->NewIntArray(MAX_WORD_LENGTH * MAX_RESULTS);
    const jintArray jScores = env->NewIntArray(MAX_RESULTS);
    const jintArray jSpaces = env->NewIntArray(MAX_RESULTS);
    const jintArray jTypes = env->NewIntArray(MAX_RESULTS);
    const jintArray jConfidence = env->NewIntArray(1);
    const jfloatArray jWeight = env->NewFloatArray(1);
    if (env->ExceptionCheck()) return nullptr;
    results.outputSuggestions(env, jCount, jPoints, jScores, jSpaces, jTypes, jConfidence,
            jWeight);
    int count = 0;
    std::vector<int> points(MAX_WORD_LENGTH * MAX_RESULTS);
    std::vector<int> scores(MAX_RESULTS);
    env->GetIntArrayRegion(jCount, 0, 1, &count);
    env->GetIntArrayRegion(jPoints, 0, MAX_WORD_LENGTH * MAX_RESULTS, points.data());
    env->GetIntArrayRegion(jScores, 0, MAX_RESULTS, scores.data());
    env->DeleteLocalRef(jCount);
    env->DeleteLocalRef(jPoints);
    env->DeleteLocalRef(jScores);
    env->DeleteLocalRef(jSpaces);
    env->DeleteLocalRef(jTypes);
    env->DeleteLocalRef(jConfidence);
    env->DeleteLocalRef(jWeight);

    // The core emits its queue lowest score first and may emit one word twice (as a correction
    // and as a whitelisted or exact match); return each word once, best first.
    std::vector<int> order;
    for (int i = 0; i < std::min(count, MAX_RESULTS); ++i) order.push_back(i);
    std::stable_sort(order.begin(), order.end(),
            [&](int a, int b) { return scores[a] > scores[b]; });
    std::vector<std::vector<int>> unique;
    std::vector<int> sortedScores;
    for (const int index : order) {
        const int *const word = &points[index * MAX_WORD_LENGTH];
        std::vector<int> text(word, std::find(word, word + MAX_WORD_LENGTH, 0));
        if (std::find(unique.begin(), unique.end(), text) != unique.end()) continue;
        unique.push_back(std::move(text));
        sortedScores.push_back(scores[index]);
    }
    const jclass stringClass = env->FindClass("java/lang/String");
    const jobjectArray words = env->NewObjectArray(static_cast<jsize>(unique.size()), stringClass,
            nullptr);
    if (!words) return nullptr;
    for (size_t i = 0; i < unique.size(); ++i) {
        const jstring text = newString(env, unique[i].data(), static_cast<int>(unique[i].size()));
        if (!text) return nullptr;
        env->SetObjectArrayElement(words, static_cast<jsize>(i), text);
        env->DeleteLocalRef(text);
    }
    env->SetIntArrayRegion(outScores, 0, static_cast<jsize>(sortedScores.size()),
            sortedScores.data());
    return words;
}

/// Returns the address of the coordinator's function table (english_api.h) as a long.
JNIEXPORT jlong JNICALL Java_com_yuanzhe_kotomo_engine_EnglishJni_api(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(KotomoEnglishGetApi());
}

/// Closes an engine handle from open.
/// Side effects: unmaps the dictionary and frees the session; the handle is invalid afterwards.
JNIEXPORT void JNICALL Java_com_yuanzhe_kotomo_engine_EnglishJni_close(
        JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}

} // extern "C"
