#!/usr/bin/env bash
# Decides whether Compose Desktop's AWT-based UI/screenshot tests (bazel jvm_tests, gradle jvmTest)
# can run here, and if so, whether they need Xvfb.
#
# Xvfb itself doesn't care whether the host session is X11 or Wayland — it's a self-contained
# virtual X server. The actual failure modes are: (1) a native Wayland session has no X11 DISPLAY
# at all until something starts XWayland, which nothing does lazily inside a plain shell/agent
# session even with xorg-xwayland installed; (2) a shell's inherited DISPLAY/XAUTHORITY (e.g. a
# stale Claude Code shell snapshot) can point at a socket that no longer exists; (3) xvfb-run may
# simply not be installed. All three look identical from the test runner: every UI test fails with
# `NoClassDefFoundError: sun.awt.X11.XToolkit` / "Can't connect to X11 window server".
#
# Usage:
#   scripts/jvm-display-check.sh                  # report only; exit 0 if UI tests can run, 1 if not
#   scripts/jvm-display-check.sh -- <command...>   # run <command...>, wrapped in xvfb-run if needed
set -euo pipefail

has_real_display=false
if [ -n "${DISPLAY:-}" ]; then
    case "$DISPLAY" in
        :*)
            # Local display ":N" or ":N.M" -> socket at /tmp/.X11-unix/XN. A remote/TCP DISPLAY
            # (host:N) has no local socket to check; assume it's valid since we can't probe it
            # without an X client tool.
            d="${DISPLAY#:}"; d="${d%%.*}"
            [ -S "/tmp/.X11-unix/X${d}" ] && has_real_display=true
            ;;
        *) has_real_display=true ;;
    esac
fi

xvfb_available=false
command -v xvfb-run >/dev/null 2>&1 && xvfb_available=true

report() {
    echo "jvm-display-check: DISPLAY=${DISPLAY:-<unset>} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-<unset>} XDG_SESSION_TYPE=${XDG_SESSION_TYPE:-<unset>}" >&2
    if $has_real_display; then
        echo "jvm-display-check: real X11 display reachable — running directly" >&2
    elif $xvfb_available; then
        echo "jvm-display-check: no reachable X11 display (Wayland-only session, headless shell, or stale DISPLAY) — wrapping with xvfb-run --auto-servernum" >&2
    else
        echo "jvm-display-check: no reachable X11 display and xvfb-run is not installed — UI/screenshot tests can't run here." >&2
        echo "  Install once: sudo pacman -S xorg-server-xvfb   (Manjaro/Arch; CI installs the 'xvfb' apt package)" >&2
        echo "  Or fall back: bazel test //kmp:business_tests / ./gradlew jvmTest --tests '*BusinessTest'  (no UI, unaffected)" >&2
        echo "  Or use:       ./gradlew testDebugUnitTest  (Robolectric — headless, needs no display at all)" >&2
    fi
}

if [ "$#" -eq 0 ]; then
    report
    { $has_real_display || $xvfb_available; } && exit 0 || exit 1
fi

[ "$1" = "--" ] && shift
report
if $has_real_display; then
    exec "$@"
elif $xvfb_available; then
    exec xvfb-run --auto-servernum "$@"
else
    exit 1
fi
