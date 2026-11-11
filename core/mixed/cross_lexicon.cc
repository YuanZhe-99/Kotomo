#include "cross_lexicon.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>

namespace kotomo::mixed {
namespace {

constexpr size_t kHeaderSize = 32;
constexpr size_t kKeySize = 8;     // u32 string offset, u32 first entry
constexpr size_t kEntrySize = 12;  // u32 surface, u32 reading, u16 cost, u8 syllables, u8 class

/// Reads a little-endian integer of 4 bytes.
uint32_t U32(const uint8_t* p) {
  return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

/// Reads a little-endian integer of 2 bytes.
uint32_t U16(const uint8_t* p) { return p[0] | (p[1] << 8); }

}  // namespace

CrossLexicon::~CrossLexicon() {
  if (map_ != nullptr) munmap(map_, map_size_);
}

bool CrossLexicon::Open(const uint8_t* data, size_t size) {
  if (size < kHeaderSize || std::memcmp(data, "KOLX", 4) != 0 || U32(data + 4) != kLexiconVersion) {
    return false;
  }
  pinyin_keys_ = U32(data + 8);
  english_keys_ = U32(data + 12);
  entries_ = U32(data + 16);
  strings_size_ = U32(data + 20);
  longest_key_ = U32(data + 24);
  // Each key table ends with a sentinel that holds the end of its last key's entries.
  const uint64_t keys_size = (uint64_t{pinyin_keys_} + english_keys_ + 2) * kKeySize;
  const uint64_t records_size = uint64_t{entries_} * kEntrySize;
  if (kHeaderSize + keys_size + records_size + strings_size_ != size) return false;
  keys_ = data + kHeaderSize;
  records_ = keys_ + keys_size;
  strings_ = reinterpret_cast<const char*>(records_ + records_size);
  return true;
}

std::unique_ptr<CrossLexicon> CrossLexicon::Map(const std::string& path, uint64_t offset,
                                                uint64_t length) {
  const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) return nullptr;
  struct stat info {};
  std::unique_ptr<CrossLexicon> lexicon;
  if (fstat(fd, &info) == 0 && offset <= static_cast<uint64_t>(info.st_size)) {
    const uint64_t rest = static_cast<uint64_t>(info.st_size) - offset;
    if (length == 0) length = rest;
    // mmap wants a page-aligned file offset; an asset inside an APK starts anywhere.
    const uint64_t page = static_cast<uint64_t>(sysconf(_SC_PAGESIZE));
    const uint64_t lead = offset % page;
    if (length <= rest && length >= kHeaderSize) {
      void* map = mmap(nullptr, length + lead, PROT_READ, MAP_PRIVATE, fd,
                       static_cast<off_t>(offset - lead));
      if (map != MAP_FAILED) {
        lexicon.reset(new CrossLexicon());
        lexicon->map_ = map;
        lexicon->map_size_ = length + lead;
        if (!lexicon->Open(static_cast<const uint8_t*>(map) + lead, length)) lexicon.reset();
      }
    }
  }
  close(fd);
  return lexicon;
}

std::unique_ptr<CrossLexicon> CrossLexicon::FromBytes(std::string bytes) {
  std::unique_ptr<CrossLexicon> lexicon(new CrossLexicon());
  lexicon->bytes_ = std::move(bytes);
  if (!lexicon->Open(reinterpret_cast<const uint8_t*>(lexicon->bytes_.data()),
                     lexicon->bytes_.size())) {
    return nullptr;
  }
  return lexicon;
}

std::unique_ptr<CrossLexicon> CrossLexicon::Empty() {
  return std::unique_ptr<CrossLexicon>(new CrossLexicon());
}

bool CrossLexicon::String(uint32_t offset, std::string_view* text) const {
  if (offset >= strings_size_) return false;
  const size_t size = static_cast<uint8_t>(strings_[offset]);
  if (size > strings_size_ - offset - 1) return false;
  *text = std::string_view(strings_ + offset + 1, size);
  return true;
}

void CrossLexicon::Find(size_t first_key, size_t keys, std::string_view key,
                        std::vector<CrossEntry>* out) const {
  out->clear();
  size_t low = 0, high = keys;
  while (low < high) {
    const size_t middle = low + (high - low) / 2;
    const uint8_t* record = keys_ + (first_key + middle) * kKeySize;
    std::string_view found;
    if (!String(U32(record), &found)) return;
    const int order = found.compare(key);
    if (order < 0) {
      low = middle + 1;
    } else if (order > 0) {
      high = middle;
    } else {
      const size_t begin = U32(record + 4), end = U32(record + kKeySize + 4);
      if (begin > end || end > entries_) return;
      for (size_t i = begin; i < end; ++i) {
        const uint8_t* entry = records_ + i * kEntrySize;
        CrossEntry e{.cost = static_cast<int32_t>(U16(entry + 8)), .syllables = entry[10]};
        if (entry[11] > static_cast<uint8_t>(CrossClass::kTransliteration) ||
            !String(U32(entry), &e.surface) || !String(U32(entry + 4), &e.reading)) {
          out->clear();
          return;
        }
        e.kind = static_cast<CrossClass>(entry[11]);
        out->push_back(e);
      }
      return;
    }
  }
}

}  // namespace kotomo::mixed
