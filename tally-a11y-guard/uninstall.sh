#!/system/bin/sh
# Stop the guard when the module is removed. The state directory (and its log) is
# intentionally left behind so a failure can be diagnosed after the fact.

STATE_DIR=/data/adb/tally-a11y-guard
PIDFILE="$STATE_DIR/guard.pid"

if [ -f "$PIDFILE" ]; then
    pid=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$pid" ]; then
        kill "$pid" 2>/dev/null
    fi
    rm -f "$PIDFILE"
fi

exit 0
