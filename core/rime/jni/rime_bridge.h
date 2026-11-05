// Kotomo's bridge to librime: one process-wide librime instance with one session.
//
// The JNI layer (rime_jni.cc) only converts types; everything that touches librime lives here so
// the host tests exercise the same code the app runs.

#ifndef KOTOMO_CORE_RIME_JNI_RIME_BRIDGE_H_
#define KOTOMO_CORE_RIME_JNI_RIME_BRIDGE_H_

#include <cstddef>
#include <string>
#include <vector>

namespace kotomo::rime_bridge {

/// Holds one candidate: its text, librime's comment for it (the spelling for pinyin candidates,
/// from the schema's spelling hints) and the type of the translation that produced it (for example
/// `phrase` for the built-in dictionary, `user_phrase` for learned words, `user_table` for Kotomo's
/// run-time dictionaries, `sentence`, `completion`, `punct`).
struct Candidate {
  std::string text;
  std::string comment;
  std::string type;
};

/// Starts librime on the given data directories and opens the session.
/// Inputs: shared_data_dir holds the read-only schema data (stage-data.cmake's output);
///   user_data_dir is writable and receives the deployed build/, the user dictionary and user.yaml.
/// Returns: true when a session is open. Before the first deploy() of a fresh user directory there
///   is nothing to load, so this may return false; call deploy() and the session opens then.
/// Side effects: calling it again shuts the previous instance down first.
/// Notes: engine thread only; librime is compiled without logging, so nothing typed reaches a log.
bool Initialize(const std::string& shared_data_dir, const std::string& user_data_dir);

/// Compiles the schemas listed in default.yaml into user_data_dir/build, then reopens the session.
/// Returns: true when every deploy task succeeded; DeployFailures() names the ones that did not.
/// Side effects: closes the session first, so the current composition is lost; writes build/,
///   installation.yaml and user.yaml. Unchanged schemas are skipped by librime's own checks.
/// Notes: runs librime's four workspace tasks (installation_update, workspace_update,
///   user_dict_upgrade, cleanup_trash) one by one rather than through RimeDeployWorkspace, whose
///   `&&` skips the schema compile when an earlier task fails; synchronous and slow (seconds on a
///   fresh directory); librime refuses key input while it deploys.
bool Deploy();

/// Forgets what the person taught librime about candidate `index` (counted from the first).
/// Returns: whether librime removed a learned entry; built-in dictionary words stay, only their
///   learned rank goes.
bool DeleteCandidate(std::size_t index);

/// Returns the deploy tasks that failed in the last Deploy(), comma-separated, or "".
std::string DeployFailures();

/// Probes the open session with fixed input and describes what librime does with it.
/// Returns: one line: the current schema, ascii_mode, the candidates for `nihao` (count/first),
///   whether the key `n` was handled and the preedit it left; or why there is no session.
/// Side effects: clears the composition before and after.
/// Notes: for the diagnostics page; it types only its own probe, never the user's text.
std::string SelfCheck();

/// Replaces the whole raw input (for example a pinyin span) and recomputes candidates.
bool SetInput(const std::string& input);

/// Sends one key to librime.
/// Inputs: keycode is an X11 keysym (`a` = 0x61, BackSpace = 0xff08, space = 0x20); mask holds
///   librime's modifier bits (kShiftMask = 1, kControlMask = 4, kReleaseMask = 1 << 30).
/// Returns: whether librime handled the key; an unhandled key belongs to the app.
bool ProcessKey(int keycode, int mask);

/// Returns: the preedit text of the composition, or empty when nothing is being composed.
std::string Composition();

/// Returns: up to `limit` candidates of the current segment, best first, across pages, each with
///   its comment and the type of its translation.
std::vector<Candidate> Candidates(std::size_t limit);

/// Selects the candidate at `index` (counted from the first candidate, not the page).
/// Returns: false if there is no such candidate.
/// Side effects: if the selection completes the input, librime commits; read it with TakeCommit().
bool SelectCandidate(std::size_t index);

/// Returns: the text librime committed since the last call, or empty; the text is consumed.
std::string TakeCommit();

/// Discards the composition without committing.
void Clear();

/// Sets a schema switch or runtime option (`zh_hans`, `ascii_mode`, `full_shape`, …).
void SetOption(const std::string& name, bool value);

/// Chooses whether typing may learn.
/// Inputs: enabled false selects luna_pinyin_simp_private, whose user dictionary is turned off, so
///   librime neither records commits nor ranks by what was learned.
/// Returns: whether the schema was selected (false before a session exists; the choice is kept and
///   applied when the session opens).
/// Side effects: discards the composition.
bool SetLearning(bool enabled);

/// Closes the session and stops librime, flushing the user dictionary.
void Shutdown();

}  // namespace kotomo::rime_bridge

#endif  // KOTOMO_CORE_RIME_JNI_RIME_BRIDGE_H_
