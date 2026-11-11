#include "mozc_client.h"

#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cstdlib>
#include <cstring>

namespace kotomo::mixed::host {
namespace {

/// Splits a line at tabs.
std::vector<std::string> Fields(const std::string& line) {
  std::vector<std::string> out;
  size_t at = 0;
  while (true) {
    const size_t tab = line.find('\t', at);
    if (tab == std::string::npos) {
      out.push_back(line.substr(at));
      return out;
    }
    out.push_back(line.substr(at, tab - at));
    at = tab + 1;
  }
}

/// Returns: true when `text` is safe inside one protocol field.
bool Plain(const std::string& text) {
  return !text.empty() && text.find_first_of("\t\n:") == std::string::npos;
}

}  // namespace

std::unique_ptr<MozcClient> MozcClient::Start(const std::string& service, const std::string& data,
                                              const std::string& profile_dir) {
  int to_child[2], from_child[2];
  if (pipe(to_child) != 0) return nullptr;
  if (pipe(from_child) != 0) {
    close(to_child[0]);
    close(to_child[1]);
    return nullptr;
  }
  signal(SIGPIPE, SIG_IGN);  // a dead service is a failed write, not the end of this process
  const pid_t pid = fork();
  if (pid < 0) return nullptr;
  if (pid == 0) {
    dup2(to_child[0], STDIN_FILENO);
    dup2(from_child[1], STDOUT_FILENO);
    close(to_child[0]);
    close(to_child[1]);
    close(from_child[0]);
    close(from_child[1]);
    execl(service.c_str(), service.c_str(), data.c_str(), profile_dir.c_str(), nullptr);
    _exit(127);
  }
  close(to_child[0]);
  close(from_child[1]);
  std::unique_ptr<MozcClient> client(new MozcClient());
  client->pid_ = pid;
  client->to_service_ = to_child[1];
  client->from_service_ = fdopen(from_child[0], "r");
  if (client->from_service_ == nullptr || client->Hiragana("a").empty()) return nullptr;
  return client;
}

MozcClient::~MozcClient() {
  if (to_service_ >= 0) {
    Send("quit");
    close(to_service_);
  }
  if (from_service_ != nullptr) fclose(from_service_);
  if (pid_ > 0) waitpid(pid_, nullptr, 0);
}

bool MozcClient::Send(const std::string& line) {
  const std::string out = line + "\n";
  size_t sent = 0;
  while (sent < out.size()) {
    const ssize_t n = write(to_service_, out.data() + sent, out.size() - sent);
    if (n <= 0) return false;
    sent += static_cast<size_t>(n);
  }
  return true;
}

bool MozcClient::Receive(std::string* line) {
  char* buffer = nullptr;
  size_t capacity = 0;
  const ssize_t n = getline(&buffer, &capacity, from_service_);
  if (n <= 0) {
    free(buffer);
    return false;
  }
  line->assign(buffer, static_cast<size_t>(n));
  free(buffer);
  if (!line->empty() && line->back() == '\n') line->pop_back();
  return true;
}

std::string MozcClient::Hiragana(const std::string& romaji) {
  std::string line;
  if (!Plain(romaji) || !Send("kana\t" + romaji) || !Receive(&line)) return {};
  return line.rfind("ok\t", 0) == 0 ? line.substr(3) : std::string();
}

std::optional<std::vector<JapaneseSegment>> MozcClient::Convert(
    const std::vector<JapaneseSpan>& spans) {
  std::string request = "convert";
  for (const JapaneseSpan& span : spans) {
    if (!Plain(span.reading) || (!span.surface.empty() && !Plain(span.surface))) {
      return std::nullopt;
    }
    request += span.surface.empty() ? "\tF:" + span.reading
                                    : "\tP:" + span.reading + ":" + span.surface;
  }
  std::string line;
  if (!Send(request) || !Receive(&line) || line.rfind("ok\t", 0) != 0) return std::nullopt;
  const long count = std::strtol(line.c_str() + 3, nullptr, 10);
  std::vector<JapaneseSegment> segments;
  for (long i = 0; i < count; ++i) {
    if (!Receive(&line)) return std::nullopt;
    const std::vector<std::string> fields = Fields(line);
    // key, pinned, then (value, reading, cost) per candidate
    if (fields.size() < 5 || (fields.size() - 2) % 3 != 0) return std::nullopt;
    JapaneseSegment segment{.reading = fields[0], .pinned = fields[1] == "1", .words = {}};
    for (size_t f = 2; f + 2 < fields.size(); f += 3) {
      segment.words.push_back({.text = fields[f],
                               .reading = fields[f + 1],
                               .cost = static_cast<int32_t>(std::strtol(fields[f + 2].c_str(), nullptr, 10))});
    }
    segments.push_back(std::move(segment));
  }
  if (segments.empty()) return std::nullopt;
  return segments;
}

}  // namespace kotomo::mixed::host
