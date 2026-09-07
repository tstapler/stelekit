#!/usr/bin/env bash
# Regression tests for tools/bazel. Run: bash tools/bazel_test.sh
#
# No test framework (this repo has none for shell scripts) — plain bash plus
# tiny stub $BAZEL_REAL scripts, so no real Bazel invocation is ever needed.
# Deliberately not `set -e`: a failing assertion should be recorded and the
# rest of the suite should still run, so one broken test doesn't hide others.
#
# All invocations below force BAZEL_NO_CI_LIMITS=1: these tests exercise the
# lock/classification logic, not the systemd cgroup wrapping, and in some
# sandboxes `systemd-run --user --scope` doesn't block for the wrapped job's
# full duration (observed directly: a wrapped 2s sleep returned in ~0s),
# which would make timing-sensitive assertions here flaky for reasons that
# have nothing to do with tools/bazel's own correctness.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BAZEL_SCRIPT="$SCRIPT_DIR/bazel"

FAILURES=0
ALL_WORKDIRS=()
WORKDIR=""

# shellcheck disable=SC2329 # invoked indirectly via the EXIT trap below
cleanup() {
  local d
  for d in "${ALL_WORKDIRS[@]:-}"; do
    [ -n "$d" ] && rm -rf "$d"
  done
}
trap cleanup EXIT

fail() {
  echo "FAIL: $1" >&2
  FAILURES=$((FAILURES + 1))
}

pass() {
  echo "PASS: $1"
}

# tools/bazel's lock is a symlink whose target is "pid:starttime" — not a
# real filesystem path — so it's always dangling. Plain `[ -e ]` follows
# symlinks and reports false for a dangling one even while it's legitimately
# held; `-L` catches the symlink itself regardless of what it points to.
lock_exists() {
  [ -e "$1" ] || [ -L "$1" ]
}

# Polls (every 0.05s, up to $2 tries, default 40 = ~2s) until
# lock_exists("$1") is true. Returns 0 as soon as it's true, 1 if the bound
# is hit first. Used instead of a fixed `sleep N` before checking lock
# state: a fixed sleep is either too short (flaky false-fail on a
# slow/loaded machine — the child hasn't reached `ln -s` yet) or, worse,
# indistinguishable from "will never happen" when asserting absence (a
# structurally weaker check that could false-PASS for the wrong reason).
wait_for_lock() {
  local lock="$1" tries="${2:-40}" i=0
  while [ "$i" -lt "$tries" ]; do
    lock_exists "$lock" && return 0
    sleep 0.05
    i=$((i + 1))
  done
  lock_exists "$lock"
}

# Watches for $2 tries (default 40 = ~2s), failing (returns 1) the instant
# lock_exists("$1") becomes true, succeeding (returns 0) only if it stays
# absent for the entire window. Used for asserting absence: a single
# fixed-delay checkpoint can't distinguish "hasn't happened yet" from "will
# never happen" — this actively watches across the whole window a positive
# case would need to appear in, rather than sampling once.
assert_lock_absent_for() {
  local lock="$1" tries="${2:-40}" i=0
  while [ "$i" -lt "$tries" ]; do
    lock_exists "$lock" && return 1
    sleep 0.05
    i=$((i + 1))
  done
  return 0
}

new_workdir() {
  WORKDIR=$(mktemp -d)
  ALL_WORKDIRS+=("$WORKDIR")
}

# A fake $BAZEL_REAL that returns immediately.
make_fast_stub() {
  local path="$1"
  cat >"$path" <<'EOF'
#!/usr/bin/env bash
echo "fake-bazel: $*"
exit 0
EOF
  chmod +x "$path"
}

# A fake $BAZEL_REAL that "runs" for $2 seconds, appending a start/end
# timestamp line (keyed by its own pid) to $1 so concurrent invocations can
# be checked for overlap afterward.
make_slow_stub() {
  local path="$1" marker="$2" sleep_secs="$3"
  cat >"$path" <<EOF
#!/usr/bin/env bash
echo "\$\$ start \$(date +%s.%N)" >> "$marker"
sleep "$sleep_secs"
echo "\$\$ end \$(date +%s.%N)" >> "$marker"
exit 0
EOF
  chmod +x "$path"
}

# Reads a two-run marker file (see make_slow_stub) and prints OK if the two
# runs' [start,end] windows don't overlap, OVERLAP if they do.
overlap_check() {
  local marker="$1"
  awk '
    $2 == "start" { s[$1] = $3 }
    $2 == "end"   { e[$1] = $3 }
    END {
      n = 0
      for (p in s) { pids[n++] = p }
      if (n != 2) { print "BADCOUNT"; exit }
      p1 = pids[0]; p2 = pids[1]
      if (e[p1] <= s[p2] || e[p2] <= s[p1]) { print "OK" } else { print "OVERLAP" }
    }
  ' "$marker"
}

test_lightweight_passthrough() {
  new_workdir
  make_fast_stub "$WORKDIR/fake-bazel"
  local lock="$WORKDIR/lock"
  if BAZEL_REAL="$WORKDIR/fake-bazel" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_NO_CI_LIMITS=1 "$BAZEL_SCRIPT" version >/dev/null 2>&1; then
    if lock_exists "$lock"; then
      fail "lightweight command ('version') created a lock file"
    else
      pass "lightweight command passes straight through, no lock created"
    fi
  else
    fail "lightweight command ('version') invocation failed"
  fi
}

test_ci_bypass() {
  new_workdir
  make_fast_stub "$WORKDIR/fake-bazel"
  local lock="$WORKDIR/lock"
  if GITHUB_ACTIONS=1 BAZEL_REAL="$WORKDIR/fake-bazel" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_TIMEOUT=1 BAZEL_NO_CI_LIMITS=1 "$BAZEL_SCRIPT" build //... >/dev/null 2>&1; then
    if lock_exists "$lock"; then
      fail "GITHUB_ACTIONS=1 heavy command ('build') still took the lock"
    else
      pass "GITHUB_ACTIONS=1 bypasses lock/cap/timeout even for a heavy command"
    fi
  else
    fail "GITHUB_ACTIONS=1 heavy command ('build') invocation failed"
  fi
}

test_run_is_interactive_not_batch() {
  new_workdir
  local slow="$WORKDIR/slow-bazel"
  local marker="$WORKDIR/marker"
  make_slow_stub "$slow" "$marker" 2
  local lock="$WORKDIR/lock"
  BAZEL_REAL="$slow" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_TIMEOUT=1 BAZEL_NO_CI_LIMITS=1 "$BAZEL_SCRIPT" run //kmp:desktop_app >/dev/null 2>&1 &
  local p=$!
  # Watch across the full 2s the slow stub runs (rather than a single
  # fixed-delay checkpoint): "no lock yet" and "will never take a lock"
  # look identical at one snapshot, so only observing absence across the
  # child's whole lifetime rules out the lock appearing late.
  if assert_lock_absent_for "$lock" 40; then
    pass "'bazel run' does not take the cross-worktree lock"
  else
    fail "'bazel run' took the cross-worktree lock — it must be interactive-only (no lock, no timeout)"
  fi
  wait "$p"
}

test_leading_startup_flag_still_classified_heavy() {
  new_workdir
  local slow="$WORKDIR/slow-bazel"
  local marker="$WORKDIR/marker"
  make_slow_stub "$slow" "$marker" 2
  local lock="$WORKDIR/lock"
  BAZEL_REAL="$slow" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_LOCK_WAIT_TIMEOUT=30 BAZEL_NO_CI_LIMITS=1 \
    "$BAZEL_SCRIPT" --output_base=/tmp/does-not-need-to-exist build //... >/dev/null 2>&1 &
  local p=$!
  # Poll rather than a single fixed-delay checkpoint: on a slow/loaded
  # machine the child may not have reached `ln -s` yet at any one instant.
  if wait_for_lock "$lock" 40; then
    pass "a leading startup flag ('--output_base=... build') is still classified as heavy (lock held during run)"
  else
    fail "a leading startup flag caused 'build' to be misclassified as non-heavy (no lock taken)"
  fi
  wait "$p"
}

test_stale_lock_with_dead_pid_reclaimed() {
  new_workdir
  make_fast_stub "$WORKDIR/fake-bazel"
  local lock="$WORKDIR/lock"

  # A pid guaranteed to be dead: spawn a trivial subshell and wait for it —
  # once wait(2) returns, that pid is reaped and no longer running.
  ( : ) &
  local dead_pid=$!
  wait "$dead_pid" 2>/dev/null || true

  ln -s "${dead_pid}:" "$lock"

  local out
  if out=$(BAZEL_REAL="$WORKDIR/fake-bazel" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_LOCK_WAIT_TIMEOUT=5 BAZEL_NO_CI_LIMITS=1 timeout 10 "$BAZEL_SCRIPT" build //... 2>&1); then
    if lock_exists "$lock"; then
      fail "stale lock (dead pid $dead_pid) was reclaimed but the new lock was never released after the run completed: $out"
    else
      pass "stale lock recorded against a dead pid ($dead_pid) is reclaimed, not stuck forever"
    fi
  else
    fail "invocation against a stale dead-pid lock failed or hung instead of reclaiming it: $out"
  fi
}

test_empty_pid_file_race_closed() {
  new_workdir
  make_fast_stub "$WORKDIR/fake-bazel"
  local lock="$WORKDIR/lock"

  # Simulates exactly what the OLD `mkdir "$lock_dir"` + later
  # `echo "$$" > "$lock_dir/pid"` code could leave behind if killed in the
  # window between those two steps: a lock path that exists but carries no
  # readable holder identity at all.
  mkdir "$lock"

  local out
  if out=$(BAZEL_REAL="$WORKDIR/fake-bazel" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_LOCK_WAIT_TIMEOUT=5 BAZEL_NO_CI_LIMITS=1 timeout 10 "$BAZEL_SCRIPT" build //... 2>&1); then
    pass "a lock path with no readable holder identity (the empty-pid-file race) is treated as reclaimable, not held forever"
  else
    fail "a lock path with no readable holder identity caused a hang/failure instead of being reclaimed: $out"
  fi
}

test_preexisting_directory_at_lock_path_reclaimed() {
  new_workdir
  make_fast_stub "$WORKDIR/fake-bazel"
  local lock="$WORKDIR/lock"

  # Simulates the OLD mkdir-based lock format (superseded by the current
  # symlink scheme) leaving a plain directory behind at this exact default
  # path — or a crash, or anything else that happens to create a plain
  # directory here. `ln -s TARGET LINKPATH` does NOT fail when LINKPATH is
  # an existing directory; it silently creates a symlink named after
  # TARGET's basename INSIDE that directory and returns exit 0, so a naive
  # "did ln -s succeed" check would wrongly believe it acquired the lock.
  mkdir "$lock"

  local out
  if out=$(BAZEL_REAL="$WORKDIR/fake-bazel" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_LOCK_WAIT_TIMEOUT=5 BAZEL_NO_CI_LIMITS=1 timeout 10 "$BAZEL_SCRIPT" build //... 2>&1); then
    pass "a pre-existing plain directory at the lock path (old-format leftover) is reclaimed, not silently defeated: $out"
  else
    fail "a pre-existing plain directory at the lock path caused a hang/failure instead of being reclaimed: $out"
  fi
}

test_preexisting_directory_at_lock_path_still_serializes() {
  new_workdir
  local slow="$WORKDIR/slow-bazel"
  local marker="$WORKDIR/marker"
  make_slow_stub "$slow" "$marker" 2
  local lock="$WORKDIR/lock"
  : >"$marker"

  # Same old-format-leftover setup as above, but this time with two
  # concurrent invocations: the real bug this regression test targets is
  # that `ln -s` "succeeding" against a pre-existing directory gives BOTH
  # concurrent invocations a false "lock acquired" signal, letting them run
  # fully overlapped — exactly the race this whole lock exists to prevent.
  mkdir "$lock"

  BAZEL_REAL="$slow" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_LOCK_WAIT_TIMEOUT=30 BAZEL_NO_CI_LIMITS=1 "$BAZEL_SCRIPT" build //a >/dev/null 2>&1 &
  local p1=$!
  sleep 0.3
  BAZEL_REAL="$slow" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_LOCK_WAIT_TIMEOUT=30 BAZEL_NO_CI_LIMITS=1 "$BAZEL_SCRIPT" build //b >/dev/null 2>&1 &
  local p2=$!

  local r1 r2
  wait "$p1"
  r1=$?
  wait "$p2"
  r2=$?

  if [ "$r1" -ne 0 ] || [ "$r2" -ne 0 ]; then
    fail "concurrent invocations against a pre-existing lock-path directory did not both succeed (exit $r1 / $r2)"
    return
  fi

  local n
  n=$(wc -l <"$marker" | tr -d ' ')
  if [ "$n" != "4" ]; then
    fail "expected 4 marker lines (2 runs x start/end), got $n: $(cat "$marker")"
    return
  fi

  local result
  result=$(overlap_check "$marker")
  case "$result" in
    OK) pass "two concurrent invocations against a pre-existing lock-path directory still correctly serialize (no overlap)" ;;
    OVERLAP) fail "two concurrent invocations against a pre-existing lock-path directory overlapped — the old-format-leftover directory defeated the lock: $(cat "$marker")" ;;
    *) fail "could not determine overlap from marker file: $(cat "$marker")" ;;
  esac
}

test_concurrent_invocations_serialize() {
  new_workdir
  local slow="$WORKDIR/slow-bazel"
  local marker="$WORKDIR/marker"
  make_slow_stub "$slow" "$marker" 2
  local lock="$WORKDIR/lock"
  : >"$marker"

  BAZEL_REAL="$slow" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_LOCK_WAIT_TIMEOUT=30 BAZEL_NO_CI_LIMITS=1 "$BAZEL_SCRIPT" build //a >/dev/null 2>&1 &
  local p1=$!
  sleep 0.3
  BAZEL_REAL="$slow" BAZEL_LOCAL_CONCURRENCY_LOCK="$lock" BAZEL_LOCK_WAIT_TIMEOUT=30 BAZEL_NO_CI_LIMITS=1 "$BAZEL_SCRIPT" build //b >/dev/null 2>&1 &
  local p2=$!

  local r1 r2
  wait "$p1"
  r1=$?
  wait "$p2"
  r2=$?

  if [ "$r1" -ne 0 ] || [ "$r2" -ne 0 ]; then
    fail "concurrent invocations did not both succeed (exit $r1 / $r2)"
    return
  fi

  local n
  n=$(wc -l <"$marker" | tr -d ' ')
  if [ "$n" != "4" ]; then
    fail "expected 4 marker lines (2 runs x start/end), got $n: $(cat "$marker")"
    return
  fi

  local result
  result=$(overlap_check "$marker")
  case "$result" in
    OK) pass "two concurrent invocations correctly serialize (no overlap)" ;;
    OVERLAP) fail "two concurrent invocations overlapped — the lock did not serialize them: $(cat "$marker")" ;;
    *) fail "could not determine overlap from marker file: $(cat "$marker")" ;;
  esac
}

main() {
  test_lightweight_passthrough
  test_ci_bypass
  test_run_is_interactive_not_batch
  test_leading_startup_flag_still_classified_heavy
  test_stale_lock_with_dead_pid_reclaimed
  test_empty_pid_file_race_closed
  test_preexisting_directory_at_lock_path_reclaimed
  test_preexisting_directory_at_lock_path_still_serializes
  test_concurrent_invocations_serialize

  echo
  if [ "$FAILURES" -eq 0 ]; then
    echo "All tests passed."
    exit 0
  else
    echo "$FAILURES test(s) failed."
    exit 1
  fi
}

main "$@"
