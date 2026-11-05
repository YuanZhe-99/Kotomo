// Host test of the JNI bridge's UTF-16 ↔ UTF-8 conversion (core/rime/jni/utf.h). The conversion
// it replaces dropped the last character on devices, so librime got ".../rime/share" for
// ".../rime/shared" and loaded no data.

#include <cstdio>
#include <string>

#include "utf.h"

namespace {

int failures = 0;

void Check(bool ok, const char* id, const std::string& detail) {
  std::printf("%s %s: %s\n", ok ? "PASS" : "FAIL", id, detail.c_str());
  if (!ok) ++failures;
}

std::string To8(const std::u16string& s) { return kotomo::utf::Utf16ToUtf8(s.data(), s.size()); }

}  // namespace

int main() {
  const std::u16string path = u"/data/user/0/com.yuanzhe.kotomo/files/rime/shared";
  Check(To8(path) == "/data/user/0/com.yuanzhe.kotomo/files/rime/shared", "S2-utf-path",
        "an ASCII path keeps its last character: " + To8(path));
  Check(To8(u"你好") == "你好", "S2-utf-cjk", "你好 survives UTF-16 → UTF-8");
  Check(To8(u"😀") == "😀" && To8(u"𠀀") == "𠀀", "S2-utf-astral", "a surrogate pair (emoji, Extension B) survives");
  Check(To8(std::u16string(1, char16_t(0xD800)) + u"a") == "\xEF\xBF\xBD" "a", "S2-utf-lone-surrogate",
        "an unpaired surrogate becomes U+FFFD and the rest stays");
  Check(To8(u"") .empty(), "S2-utf-empty", "empty stays empty");
  Check(kotomo::utf::Utf8ToUtf16("你好😀z") == u"你好😀z", "S2-utf-back", "UTF-8 → UTF-16 keeps CJK, emoji and the last ASCII letter");
  Check(kotomo::utf::Utf8ToUtf16("a\xFF" "b") == u"a�b", "S2-utf-invalid", "an invalid byte becomes U+FFFD");
  Check(kotomo::utf::Utf8ToUtf16("a\xE4\xBD") == u"a��", "S2-utf-truncated",
        "a truncated sequence at the end becomes U+FFFD, nothing is read past the end");
  Check(kotomo::utf::Utf8ToUtf16("\xC0\xAF") == u"��", "S2-utf-overlong", "an overlong form is rejected");
  std::printf("%s: %d failure(s)\n", failures == 0 ? "PASS" : "FAIL", failures);
  return failures == 0 ? 0 : 1;
}
