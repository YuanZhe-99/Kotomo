/* The narrow C interface of libkotomo_english.so for the mixed-input coordinator (core/mixed,
 * doc/en-us/engine-contract.md): a table of plain C functions. The library exports nothing besides
 * its JNI entry points; the app reads the table's address through English.kt (`api`) and hands it
 * to the coordinator's library with the engine's handle, so no symbol is resolved across
 * libraries (ADR-008).
 *
 * Plain C types only. Engine thread only. */

#ifndef KOTOMO_CORE_ENGLISH_JNI_ENGLISH_API_H_
#define KOTOMO_CORE_ENGLISH_JNI_ENGLISH_API_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Names the layout of KotomoEnglishApi; a caller refuses a table with another version. */
#define KOTOMO_ENGLISH_API_VERSION 1u

/** Receives one spelling of a word as the dictionary writes it: ASCII `text` of `size` bytes (not
 * terminated), its unigram probability (0–255, higher is more common) and whether the dictionary
 * marks it possibly offensive. */
typedef void (*KotomoEnglishFormSink)(void* context, const char* text, size_t size,
                                      int probability, int sensitive);

/** The functions; every pointer is set. */
typedef struct KotomoEnglishApi {
  uint32_t version; /* KOTOMO_ENGLISH_API_VERSION */

  /** Looks `word` (lower-case ASCII letters, `size` bytes) up exactly, without correction, and
   * passes the dictionary's spellings of it (lower case, capitalized, upper case) to `sink`, most
   * probable first; nothing when it is not a word.
   * Inputs: `engine` is the handle EnglishJni.open returned, still open. */
  void (*lookup)(void* engine, const char* word, size_t size, void* context,
                 KotomoEnglishFormSink sink);
} KotomoEnglishApi;

/** Returns: the library's table; it lives as long as the library. */
const KotomoEnglishApi* KotomoEnglishGetApi(void);

#ifdef __cplusplus
}
#endif

#endif /* KOTOMO_CORE_ENGLISH_JNI_ENGLISH_API_H_ */
