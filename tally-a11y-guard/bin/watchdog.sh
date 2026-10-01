#!/system/bin/sh
# Keeps the guard alive across the whole boot.
#
# Magisk runs service.sh exactly once, so if the guard is ever killed nothing brings it
# back and keep-alive stays dead until the next reboot. This loop does nothing but sleep
# and re-check; it is deliberately tiny so it is itself hard to kill and cheap to keep.
#
# It never starts a second guard: it reuses exactly the same "is this pid really our
# script" test the guard and service.sh use.

MODDIR=${0%/*}
MODULE_DIR=${MODDIR%/*}
STATE_DIR=/data/adb/tally-a11y-guard
PIDFILE="$STATE_DIR/guard.pid"
WATCHDOG_PIDFILE="$STATE_DIR/watchdog.pid"
DISABLE_FLAG=/data/adb/tally-a11y-guard.disabled
GUARD="$MODDIR/guard.sh"
LOG="$STATE_DIR/guard.log"
# 与 guard.sh 共用同一个配置文件，调整耗电不必重新打包模块。
WATCHDOG_CONFIG=/data/adb/tally-a11y-guard/config
[ -f "$WATCHDOG_CONFIG" ] && . "$WATCHDOG_CONFIG" 2>/dev/null

INTERVAL=${WATCHDOG_INTERVAL:-60}

mkdir -p "$STATE_DIR" 2>/dev/null

is_running_guard() {
    [ -n "$1" ] || return 1
    kill -0 "$1" 2>/dev/null || return 1
    cmd=$(tr '\0' ' ' </proc/"$1"/cmdline 2>/dev/null)
    case "$cmd" in
        *tally_a11y_guard*) return 0 ;;
        *) return 1 ;;
    esac
}

# Single instance, same pid-reuse caution as everywhere else.
#
# The pidfile alone is racy: service.sh double-forks this script and two copies can both
# read a stale pidfile before either writes its own, which leaves several watchdogs alive
# forever (each one a permanent 60s wake-up). Also scan the process table - that closes the
# window, and it is a one-off cost at startup.
if [ -f "$WATCHDOG_PIDFILE" ]; then
    old=$(cat "$WATCHDOG_PIDFILE" 2>/dev/null)
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
        oldcmd=$(tr '\0' ' ' </proc/"$old"/cmdline 2>/dev/null)
        case "$oldcmd" in *watchdog.sh*) exit 0 ;; esac
    fi
fi
for other in $(ps -A -o PID,ARGS 2>/dev/null | grep 'watchdog\.sh' | awk '{print $1}'); do
    [ "$other" = "$$" ] && continue
    kill -0 "$other" 2>/dev/null && exit 0
done
echo $$ >"$WATCHDOG_PIDFILE"

while true; do
    sleep "$INTERVAL"
    # A disabled module must stay disabled.
    [ -f "$DISABLE_FLAG" ] && continue
    # Module removed underneath us -> stop too.
    [ -f "$GUARD" ] || exit 0
    if ! is_running_guard "$(cat "$PIDFILE" 2>/dev/null)"; then
        echo "$(date '+%Y-%m-%d %H:%M:%S') watchdog: guard gone, restarting" >>"$LOG"
        ( sh "$GUARD" >/dev/null 2>&1 & ) &
        sleep 5
    fi
done
