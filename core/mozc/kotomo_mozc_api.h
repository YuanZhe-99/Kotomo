/* The narrow C interface of libkotomo_mozc.so for the mixed-input coordinator
 * (core/mixed, doc/en-us/engine-contract.md): a table of plain C functions.
 * The library exports nothing besides its JNI entry points; the app reads the
 * table's address through Mozc.kt (`api`) and hands it to the coordinator's
 * library with the engine's handle, so no symbol is resolved across libraries
 * (ADR-008).
 *
 * Plain C types only: Mozc's library carries its own C++ runtime, protobuf and
 * abseil, and none of their objects cross. Engine thread only. */

#ifndef KOTOMO_CORE_MOZC_KOTOMO_MOZC_API_H_
#define KOTOMO_CORE_MOZC_KOTOMO_MOZC_API_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Names the layout of KotomoMozcApi; a caller refuses a table with another
 * version. */
#define KOTOMO_MOZC_API_VERSION 3u

/** Describes one span of a reading: UTF-8 hiragana, and the surface it is
 * pinned to (`surface_size` 0: Mozc converts the span). Not terminated. */
typedef struct KotomoMozcSpan {
  const char* reading;
  size_t reading_size;
  const char* surface;
  size_t surface_size;
  int fixed; /* with no surface: non-zero keeps the span one segment */
} KotomoMozcSpan;

/** Receives a conversion, segment by segment in reading order: `segment` once
 * per segment, then `word` once per candidate of it, best first. Strings are
 * UTF-8, not terminated, and valid only during the call. */
typedef struct KotomoMozcSink {
  void* context;
  void (*segment)(void* context, const char* reading, size_t reading_size,
                  int pinned);
  void (*word)(void* context, const char* text, size_t text_size,
               const char* reading, size_t reading_size, int32_t cost);
} KotomoMozcSink;

/** The functions; every pointer is set. `engine` is the handle Mozc.kt holds,
 * still open. */
typedef struct KotomoMozcApi {
  uint32_t version; /* KOTOMO_MOZC_API_VERSION */

  /** Maps romaji (`size` bytes) to hiragana with Mozc's romaji table; letters
   * the table cannot map stay ASCII.
   * Returns: the bytes of the result; at most `capacity` of them were written
   * to `out` (not terminated), so a larger return value means it was cut. */
  size_t (*hiragana)(void* engine, const char* romaji, size_t size, char* out,
                     size_t capacity);

  /** Converts a reading whose pinned spans keep their surface
   * (MozcEngine::PinnedConvert) and passes the segments to `sink`.
   * Inputs: `incognito` non-zero for a field that must leave no trace.
   * Returns: 1 when Mozc converted, 0 when it did not (the sink was not
   * called). Nothing is committed, so nothing is learned either way. */
  int (*convert)(void* engine, const KotomoMozcSpan* spans, size_t count,
                 int incognito, const KotomoMozcSink* sink);

  /** Looks a hiragana reading (`size` bytes) up as a key of Mozc's dictionary
   * (MozcEngine::WordCost), for the fuzzy kana rules.
   * Returns: the lowest word cost among the entries read exactly so, or -1
   * when there is none. */
  int32_t (*known)(void* engine, const char* reading, size_t size);
} KotomoMozcApi;

/** Returns: the library's table; it lives as long as the library. */
const KotomoMozcApi* KotomoMozcGetApi(void);

#ifdef __cplusplus
}
#endif

#endif /* KOTOMO_CORE_MOZC_KOTOMO_MOZC_API_H_ */
