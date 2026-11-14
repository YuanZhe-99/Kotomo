/* The narrow C interface of libkotomo_rime.so for the mixed-input coordinator (core/mixed,
 * doc/en-us/engine-contract.md): a table of plain C functions. The library exports nothing besides
 * its JNI entry points; the app reads the table's address through Rime.kt (`api`) and hands it to
 * the coordinator's library, so no symbol is resolved across libraries (ADR-008).
 *
 * Plain C types only: each engine library carries its own C++ runtime, so no C++ object crosses.
 * Engine thread only, like everything else that touches librime. */

#ifndef KOTOMO_CORE_RIME_JNI_KOTOMO_RIME_API_H_
#define KOTOMO_CORE_RIME_JNI_KOTOMO_RIME_API_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Names the layout of KotomoRimeApi; a caller refuses a table with another version. */
#define KOTOMO_RIME_API_VERSION 2u

/** Holds one pinyin syllable spelled by a prefix of the letters asked about. */
typedef struct KotomoRimeSpelling {
  uint8_t length; /* letters of the prefix */
  uint8_t fuzzy;  /* 1: only a fuzzy-pinyin rule or a correction of the schema derives it */
  uint8_t partial; /* 1: not a whole syllable: an abbreviation of the schema, or all the letters
                    * asked about when they only begin a syllable */
} KotomoRimeSpelling;

/** Receives one conversion of a pinyin run: UTF-8 `text` of `size` bytes (not terminated);
 * `composed` is 1 when librime made the text up from shorter words. */
typedef void (*KotomoRimeWordSink)(void* context, const char* text, size_t size, int composed);

/** The functions; every pointer is set. */
typedef struct KotomoRimeApi {
  uint32_t version; /* KOTOMO_RIME_API_VERSION */

  /** Lists the syllables the start of `letters` (lower case, `size` bytes) spells in the open
   * session's schema, fuzzy rules and corrections included, and its unfinished ones
   * (`partial`).
   * Returns: how many were written to `out`, at most `capacity`; 0 without a session. */
  size_t (*spellings)(const char* letters, size_t size, KotomoRimeSpelling* out, size_t capacity);

  /** Converts one pinyin run, given as spellings joined by apostrophes (`ni'hao`), and passes up
   * to `limit` conversions of the whole run to `sink`, best first.
   * Side effects: replaces and then clears the session's composition; nothing is committed, so
   * nothing is learned. */
  void (*convert)(const char* syllables, size_t size, size_t limit, void* context,
                  KotomoRimeWordSink sink);
} KotomoRimeApi;

/** Returns: the library's table; it lives as long as the library. */
const KotomoRimeApi* KotomoRimeGetApi(void);

#ifdef __cplusplus
}
#endif

#endif /* KOTOMO_CORE_RIME_JNI_KOTOMO_RIME_API_H_ */
