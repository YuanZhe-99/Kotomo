// UTF-16 ↔ UTF-8 for the JNI bridge, without JNI so the host tests can check it.
//
// Java strings cross JNI as UTF-16 (GetStringChars / NewString) rather than JNI's modified UTF-8, so
// characters outside the BMP (CJK Extension B and later, emoji) survive. Malformed input from
// other apps never throws: unpaired surrogates and invalid bytes become U+FFFD.
//
// Notes: written out by hand; the utf8-cpp conversion used before dropped the last character on
// devices (the shared data path arrived as ".../rime/share" and librime found no data).

#ifndef KOTOMO_RIME_UTF_H_
#define KOTOMO_RIME_UTF_H_

#include <string>

namespace kotomo::utf {

/// Appends code point `cp` to `out` as UTF-8.
inline void AppendUtf8(std::string& out, char32_t cp) {
  if (cp < 0x80) {
    out += static_cast<char>(cp);
  } else if (cp < 0x800) {
    out += static_cast<char>(0xC0 | (cp >> 6));
    out += static_cast<char>(0x80 | (cp & 0x3F));
  } else if (cp < 0x10000) {
    out += static_cast<char>(0xE0 | (cp >> 12));
    out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
    out += static_cast<char>(0x80 | (cp & 0x3F));
  } else {
    out += static_cast<char>(0xF0 | (cp >> 18));
    out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
    out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
    out += static_cast<char>(0x80 | (cp & 0x3F));
  }
}

/// Converts `length` UTF-16 units to UTF-8; an unpaired surrogate becomes U+FFFD.
inline std::string Utf16ToUtf8(const char16_t* units, std::size_t length) {
  std::string out;
  out.reserve(length);
  for (std::size_t i = 0; i < length; ++i) {
    const char32_t u = units[i];
    if (u >= 0xD800 && u <= 0xDBFF && i + 1 < length && units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF) {
      AppendUtf8(out, 0x10000 + ((u - 0xD800) << 10) + (units[i + 1] - 0xDC00));
      ++i;
    } else if (u >= 0xD800 && u <= 0xDFFF) {
      AppendUtf8(out, 0xFFFD);
    } else {
      AppendUtf8(out, u);
    }
  }
  return out;
}

/// Converts UTF-8 to UTF-16; each invalid or truncated sequence becomes U+FFFD.
inline std::u16string Utf8ToUtf16(const std::string& in) {
  std::u16string out;
  out.reserve(in.size());
  std::size_t i = 0;
  while (i < in.size()) {
    const unsigned char b = static_cast<unsigned char>(in[i]);
    int extra = b < 0x80 ? 0 : (b >> 5) == 0x6 ? 1 : (b >> 4) == 0xE ? 2 : (b >> 3) == 0x1E ? 3 : -1;
    char32_t cp = extra == 0 ? b : extra == 1 ? (b & 0x1F) : extra == 2 ? (b & 0x0F) : (b & 0x07);
    bool valid = extra >= 0 && i + extra < in.size();
    for (int k = 1; valid && k <= extra; ++k) {
      const unsigned char c = static_cast<unsigned char>(in[i + k]);
      if ((c >> 6) != 0x2) valid = false;
      else cp = (cp << 6) | (c & 0x3F);
    }
    // Overlong forms, surrogates and values beyond U+10FFFF are invalid too.
    if (valid && ((extra == 1 && cp < 0x80) || (extra == 2 && cp < 0x800) || (extra == 3 && cp < 0x10000) ||
                  (cp >= 0xD800 && cp <= 0xDFFF) || cp > 0x10FFFF)) {
      valid = false;
    }
    if (!valid) {
      out += u'�';
      ++i;
      continue;
    }
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out += static_cast<char16_t>(0xD800 + (cp >> 10));
      out += static_cast<char16_t>(0xDC00 + (cp & 0x3FF));
    } else {
      out += static_cast<char16_t>(cp);
    }
    i += extra + 1;
  }
  return out;
}

}  // namespace kotomo::utf

#endif  // KOTOMO_RIME_UTF_H_
