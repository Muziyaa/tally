#!/system/bin/sh
# Magisk late_start service stage.
#
# This script MUST return quickly: Magisk runs it synchronously during boot.
# All long-running work is handed off to detached children.

MODDIR=${0%/*}
[ -f "$MODDIR/bin/guard.sh" ] || exit 0

STATE_DIR=/data/adb/tally-a11y-guard
PIDFILE="$STATE_DIR/guard.pid"

# Is $1 actually running OUR script? A bare `kill -0` is not enough: /data/adb survives
# reboots and pids are recycled from low numbers, so on the next boot a stale pidfile
# commonly points at some unrelated process. `kill -0` would then succeed, this script
# would exit, and the guard would never start for that whole boot.
is_running_guard() {
    [ -n "$1" ] || return 1
    kill -0 "$1" 2>/dev/null || return 1
    cmd=$(tr '\0' ' ' </proc/"$1"/cmdline 2>/dev/null)
    case "$cmd" in
        *tally_a11y_guard*) return 0 ;;
        *) return 1 ;;
    esac
}

# Already running? Nothing to do.
if [ -f "$PIDFILE" ]; then
    if is_running_guard "$(cat "$PIDFILE" 2>/dev/null)"; then
        exit 0
    fi
fi

# Detach so the guard outlives this boot script.
( sh "$MODDIR/bin/guard.sh" >/dev/null 2>&1 & ) &

# Magisk runs this script exactly once per boot, so a guard killed later - an aggressive
# ROM background cleaner is the realistic case - would stay dead until the next reboot.
# The watchdog is the smallest thing that closes that hole.
if [ -f "$MODDIR/bin/watchdog.sh" ]; then
    ( sh "$MODDIR/bin/watchdog.sh" >/dev/null 2>&1 & ) &
fi

exit 0
