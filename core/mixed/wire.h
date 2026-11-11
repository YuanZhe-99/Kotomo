// The byte encoding the engine ↔ app contract's two directions share
// (doc/en-us/engine-contract.md): little-endian integers and strings with a u16 byte length.

#ifndef KOTOMO_CORE_MIXED_WIRE_H_
#define KOTOMO_CORE_MIXED_WIRE_H_

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <string_view>
#include <type_traits>

namespace kotomo::mixed {

/// Appends little-endian integers and length-prefixed strings to a buffer.
class Writer {
 public:
  explicit Writer(std::string* out) : out_(out) {}

  template <typename T>
  void Int(T value) {
    const auto bits = static_cast<std::make_unsigned_t<T>>(value);
    for (size_t i = 0; i < sizeof(T); ++i) {
      out_->push_back(static_cast<char>((bits >> (8 * i)) & 0xff));
    }
  }

  void Str(std::string_view text) {
    const size_t size = std::min<size_t>(text.size(), std::numeric_limits<uint16_t>::max());
    Int(static_cast<uint16_t>(size));
    out_->append(text.substr(0, size));
  }

 private:
  std::string* out_;
};

/// Reads what Writer wrote; every read fails once the buffer is exhausted.
class Reader {
 public:
  explicit Reader(std::string_view in) : in_(in) {}

  template <typename T>
  bool Int(T* value) {
    if (in_.size() - pos_ < sizeof(T)) return false;
    std::make_unsigned_t<T> bits = 0;
    for (size_t i = 0; i < sizeof(T); ++i) {
      bits |= static_cast<std::make_unsigned_t<T>>(static_cast<uint8_t>(in_[pos_ + i])) << (8 * i);
    }
    pos_ += sizeof(T);
    *value = static_cast<T>(bits);
    return true;
  }

  bool Str(std::string* text) {
    uint16_t size = 0;
    if (!Int(&size) || in_.size() - pos_ < size) return false;
    text->assign(in_.substr(pos_, size));
    pos_ += size;
    return true;
  }

  bool Done() const { return pos_ == in_.size(); }

 private:
  std::string_view in_;
  size_t pos_ = 0;
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_WIRE_H_
