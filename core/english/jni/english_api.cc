// The coordinator's question to the English dictionary; see english_api.h.

#include "english_api.h"

#include <algorithm>
#include <string>
#include <vector>

#include "defines.h"
#include "dictionary/property/word_attributes.h"
#include "english_engine.h"
#include "utils/int_array_view.h"

namespace kotomo::english {
namespace {

/// Holds one spelling found.
struct Form {
    std::string text;
    int probability;
    bool sensitive;
};

/// Appends `spelling` when the dictionary holds exactly it as a word.
void Add(const latinime::DictionaryStructureWithBufferPolicy *dictionary,
        const std::string &spelling, std::vector<Form> *forms) {
    const std::vector<int> points(spelling.begin(), spelling.end());
    const int id = dictionary->getWordId(latinime::CodePointArrayView(points),
            false /* forceLowerCaseSearch */);
    if (id == NOT_A_WORD_ID) return;
    const auto attributes = dictionary->getWordAttributesInContext(
            latinime::WordIdArrayView(), id, nullptr /* multiBigramMap */);
    if (attributes.isNotAWord() || attributes.isBlacklisted()) return;
    forms->push_back({spelling, attributes.getProbability(), attributes.isPossiblyOffensive()});
}

void ApiLookup(void *engine, const char *word, size_t size, void *context,
        KotomoEnglishFormSink sink) {
    const auto *const opened = static_cast<const Engine *>(engine);
    if (!opened || !opened->dictionary) return;
    Lookup(opened->dictionary->getDictionaryStructurePolicy(), std::string_view(word, size),
            context, sink);
}

const KotomoEnglishApi kApi = {KOTOMO_ENGLISH_API_VERSION, ApiLookup};

} // namespace

void Lookup(const latinime::DictionaryStructureWithBufferPolicy *dictionary, std::string_view word,
        void *context, KotomoEnglishFormSink sink) {
    if (word.empty() || word.size() > MAX_WORD_LENGTH) return;
    if (!std::all_of(word.begin(), word.end(), [](char c) { return c >= 'a' && c <= 'z'; })) return;
    std::vector<Form> forms;
    std::string spelling(word);
    Add(dictionary, spelling, &forms);
    spelling[0] = static_cast<char>(spelling[0] - 'a' + 'A');
    Add(dictionary, spelling, &forms);
    if (spelling.size() > 1) {
        for (char &c : spelling) c = c >= 'a' && c <= 'z' ? static_cast<char>(c - 'a' + 'A') : c;
        Add(dictionary, spelling, &forms);
    }
    std::stable_sort(forms.begin(), forms.end(),
            [](const Form &a, const Form &b) { return a.probability > b.probability; });
    for (const Form &form : forms) {
        sink(context, form.text.data(), form.text.size(), form.probability, form.sensitive ? 1 : 0);
    }
}

} // namespace kotomo::english

extern "C" const KotomoEnglishApi *KotomoEnglishGetApi(void) { return &kotomo::english::kApi; }
