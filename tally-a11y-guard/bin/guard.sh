#!/system/bin/sh
# tally-a11y-guard - keep Tally's accessibility service bound.
#
# WHY THIS EXISTS
#   Tally records transactions from an AccessibilityService. While that service is
#   bound, ActivityManagerService treats the process as important: it cannot even be
#   killed with `am kill`. Some ROMs (notably HyperOS/MIUI) silently drop the
#   binding; once dropped the process becomes killable and auto-recording stops.
#
#   Re-writing Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES makes
#   AccessibilityManagerService re-evaluate and re-bind the service, which makes the
#   SYSTEM start Tally again. Measured on device: process back up in ~1s, and it works
#   even from the post-force-stop "stopped" state.
#
# DESIGN CONSTRAINTS (deliberate - do not "optimise" these away)
#   * Poll based. No inotify dependency: inotify is unavailable on some kernels and
#     emulators, and a `settings get` costs <10ms, so 30s polling is ~0 battery.
#   * Never touches oom_adj / oom_score_adj. Raising process priority is the classic
#     mistake: it unbalances lmkd, raises standby drain and destabilises the system.
#   * No wakelocks, no foreground service, no busy loop.
#   * MERGES into enabled_accessibility_services. It must never clobber the user's
#     other accessibility services (TalkBack, 李跳跳, ...).
#
# SAFETY
#   Create /data/adb/tally-a11y-guard.disabled to stop the guard from doing anything.

PKG="com.example.budgetapp"
SVC_CLASS="com.google.android.accessibility.selecttospeak.SelectToSpeakService"
COMPONENT="$PKG/$SVC_CLASS"

STATE_DIR="/data/adb/tally-a11y-guard"
LOG="$STATE_DIR/guard.log"
PIDFILE="$STATE_DIR/guard.pid"
DISABLE_FLAG="/data/adb/tally-a11y-guard.disabled"

# <module>/bin/guard.sh -> <module>
SCRIPT_DIR=${0%/*}
MODULE_DIR=${SCRIPT_DIR%/*}

# Overridable only so tests can run fast; production values are the defaults.
# 可调参数。除了环境变量，还支持一个设备上的配置文件，这样调整耗电不需要重新打包模块：
#
#   echo 'POLL_SECONDS=60' >> /data/adb/tally-a11y-guard/config
#   sh /data/adb/modules/tally_a11y_guard/service.sh     # 重启守护生效
#
# 优先级：环境变量 > 配置文件 > 下面的默认值。
GUARD_CONFIG=/data/adb/tally-a11y-guard/config
[ -f "$GUARD_CONFIG" ] && . "$GUARD_CONFIG" 2>/dev/null

POLL_SECONDS=${POLL_SECONDS:-30}
DEEP_CHECK_EVERY=${DEEP_CHECK_EVERY:-10}   # every N polls (~5min) check the real binding
LOG_MAX_BYTES=262144      # 256 KiB, then rotate once to guard.log.1

# The service's android:label, exactly as declared in AndroidManifest.xml. AMS prints
# bound services by LABEL only (no package, no class), so this string is the only way to
# tell whether OUR service - rather than some other accessibility service - is bound.
SVC_LABEL="记账屏幕同步助手"

# Where the app stores the user's automation choices. Root can read it, so the guard
# follows the in-app switches without needing a second protocol.
APP_PREFS="/data/data/$PKG/shared_prefs/app_prefs.xml"
BIND_CHECK_EVERY=${BIND_CHECK_EVERY:-1}   # binding health every N polls (1 = 30s)
AUTOMATION_EVERY=${AUTOMATION_EVERY:-2}   # re-apply the permission choices every N polls

mkdir -p "$STATE_DIR" 2>/dev/null

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$LOG" 2>/dev/null
}

rotate_log() {
    [ -f "$LOG" ] || return 0
    size=$(wc -c <"$LOG" 2>/dev/null)
    if [ -n "$size" ] && [ "$size" -gt "$LOG_MAX_BYTES" ]; then
        mv -f "$LOG" "$LOG.1" 2>/dev/null
    fi
}

get_services() {
    settings get secure enabled_accessibility_services 2>/dev/null
}

# True when the settings provider actually answers us.
#
# Judged by whether `settings get` itself SUCCEEDS, never by the value: an unset key
# legitimately reads back as "null" (or "" on some builds), and that must mean
# "accessibility was never switched on - go switch it on", NOT "provider unavailable".
# Treating "null" as "not ready" would silently disable every repair on such devices -
# which is exactly the "无障碍未开启时不会自动开启" symptom.
settings_ready() {
    settings get secure accessibility_enabled >/dev/null 2>&1
}

wait_for_boot() {
    n=0
    while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
        [ "$n" -ge 90 ] && return 1     # ~180s
        sleep 2
        n=$((n + 1))
    done
    return 0
}

wait_for_settings() {
    n=0
    while ! settings_ready; do
        [ "$n" -ge 60 ] && return 1     # ~120s
        sleep 2
        n=$((n + 1))
    done
    return 0
}

has_component() {
    case "$1" in
        *"$COMPONENT"*) return 0 ;;
        *) return 1 ;;
    esac
}

is_enabled() {
    [ "$(settings get secure accessibility_enabled 2>/dev/null)" = "1" ]
}

app_alive() {
    pidof "$PKG" >/dev/null 2>&1
}

# Deep health check of the accessibility binding, from a single dumpsys call.
#
# Sets binding_reason and returns 0 (unhealthy -> needs a rebind) when any of:
#   * nothing is bound at all, or
#   * OUR service is missing from the bound list - the setting can still read perfectly
#     while the binding is silently gone, which is exactly "无障碍开着却记不上账", or
#   * AMS is holding a crashed service.
# Every branch is unambiguous, so we never toggle accessibility on a hunch: a rebind
# briefly disconnects every accessibility service on the device.
binding_reason=""

# Authoritative cross-check: does ActivityManager think our service holds a real binding?
#
# `dumpsys accessibility` prints bound services by LABEL, so a renamed service would look
# permanently "unbound" and we would rebind forever - and every rebind briefly disconnects
# ALL accessibility services on the device. Confirm before acting.
our_service_bound_authoritative() {
    out=$(dumpsys activity services "$PKG" 2>/dev/null) || return 1
    printf '%s\n' "$out" | grep -q 'hasBound=true'
}

# Deep health check of the accessibility binding.
#
# Sets binding_reason and returns 0 (unhealthy -> needs a rebind) when any of:
#   * nothing is bound at all, or
#   * OUR service is missing from the bound list - the setting can still read perfectly
#     while the binding is silently gone, which is exactly "无障碍开着却记不上账", or
#   * AMS is holding a crashed service.
# Every branch is unambiguous, so we never toggle accessibility on a hunch.
binding_unhealthy() {
    binding_reason=""
    dump=$(dumpsys accessibility 2>/dev/null) || return 1
    [ -n "$dump" ] || return 1

    # IMPORTANT: once more than one service is bound, the Bound services block WRAPS
    # ACROSS LINES (verified on Android 13):
    #     Bound services:{Service[label=BstCommandProcessor, ...],
    #                     Service[label=记账屏幕同步助手, ...]}
    # Matching only the "Bound services:" line therefore sees just the FIRST service and
    # reports ours as missing. Extract the whole block instead.
    block=$(printf '%s\n' "$dump" | awk '/Bound services:/{f=1} /Enabled services:/{f=0} f')

    if printf '%s\n' "$block" | grep -q 'Bound services:{}'; then
        binding_reason="no accessibility service is bound"
        return 0
    fi

    if ! printf '%s\n' "$block" | grep -q "$SVC_LABEL"; then
        if our_service_bound_authoritative; then
            return 1
        fi
        binding_reason="our service is not among the bound services"
        return 0
    fi

    crashed=$(printf '%s\n' "$dump" \
        | sed -n 's/.*Crashed services:\(.*\)/\1/p' \
        | head -1 | tr -d ' ')
    if [ -n "$crashed" ] && [ "$crashed" != "{}" ]; then
        binding_reason="AMS reports a crashed service"
        return 0
    fi
    return 1
}

# ---------------------------------------------------------------------------
# Permission automation.
#
# The in-app switches choose allow / deny / ignore per permission. The guard enforces
# that choice because these cannot reliably be flipped from inside the app itself:
# SYSTEM_ALERT_WINDOW is an appop, "后台弹出界面" is a vendor (HyperOS/MIUI) appop, and
# the Doze whitelist is a dumpsys switch.
# ---------------------------------------------------------------------------
pref_str() {
    [ -f "$APP_PREFS" ] || return 0
    sed -n "s/.*<string name=\"$1\">\([^<]*\)<\/string>.*/\1/p" "$APP_PREFS" 2>/dev/null | head -1
}

# Read an appop's effective mode. An op that was never touched still answers with a
# usable line, so "unknown" really does mean "this ROM does not know this op".
appops_mode() {
    out=$(cmd appops get "$PKG" "$1" 2>/dev/null | head -1)
    case "$out" in
        *": allow"*)  echo allow ;;
        *": deny"*)   echo deny ;;
        *": ignore"*) echo ignore ;;
        *)            echo unknown ;;
    esac
}

apply_appop() {   # $1=op  $2=allow|deny
    cur=$(appops_mode "$1")
    [ "$cur" = "$2" ] && return 0
    if cmd appops set "$PKG" "$1" "$2" >/dev/null 2>&1; then
        log "PERM $1 -> $2 (was $cur)"
        return 0
    fi
    return 1
}

apply_overlay() {
    want=$(pref_str auto_perm_overlay)
    # 缺省即「允许」：权限默认打开是产品默认行为，守护在 App 首次写入之前也要照做。
    [ -z "$want" ] && want=allow
    case "$want" in allow|deny) ;; *) return 0 ;; esac
    apply_appop SYSTEM_ALERT_WINDOW "$want"
}

# HyperOS/MIUI expose "后台弹出界面" as a vendor appop, but neither its name nor its
# numeric code is stable across versions. Probe the known spellings once, remember
# whichever the ROM accepts, and if none works say so once instead of spamming the log.
BG_POPUP_CANDIDATES="BACKGROUND_START_ACTIVITY OP_BACKGROUND_START_ACTIVITY 10021"
bg_popup_op=""
bg_popup_warned=""

apply_bg_popup() {
    want=$(pref_str auto_perm_bgpopup)
    # 缺省即「允许」：权限默认打开是产品默认行为，守护在 App 首次写入之前也要照做。
    [ -z "$want" ] && want=allow
    case "$want" in allow|deny) ;; *) return 0 ;; esac

    if [ -z "$bg_popup_op" ]; then
        [ -n "$bg_popup_warned" ] && return 1
        for cand in $BG_POPUP_CANDIDATES; do
            if cmd appops set "$PKG" "$cand" "$want" >/dev/null 2>&1; then
                bg_popup_op="$cand"
                log "PERM 后台弹出界面 -> $want (appop '$cand')"
                return 0
            fi
        done
        bg_popup_warned=1
        log "PERM 后台弹出界面: no matching appop on this ROM; set it by hand (the app has a shortcut)"
        return 1
    fi
    apply_appop "$bg_popup_op" "$want"
}

apply_battery() {
    want=$(pref_str auto_perm_battery)
    # 缺省即「允许」：权限默认打开是产品默认行为，守护在 App 首次写入之前也要照做。
    [ -z "$want" ] && want=allow
    case "$want" in allow|deny) ;; *) return 0 ;; esac
    listed=$(dumpsys deviceidle whitelist 2>/dev/null | grep -c "$PKG")
    if [ "$want" = "allow" ]; then
        [ "$listed" -gt 0 ] && return 0
        dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 && log "PERM Doze whitelist +$PKG"
    else
        [ "$listed" -eq 0 ] && return 0
        dumpsys deviceidle whitelist -"$PKG" >/dev/null 2>&1 && log "PERM Doze whitelist -$PKG"
    fi
}

# 通知权限。Android 13+ 要显式授予，没它的话记账提示只能退化成系统 Toast
# —— 而 Toast 没有「撤销」按钮、也不能点进 App。App 首次启动会自己申请一次，
# 但用户点了「不允许」之后就再也不会问了，所以守护这里再兜一道。
apply_notification() {
    want=$(pref_str auto_perm_notification)
    # 缺省即「允许」，和其它权限保持一致。
    [ -z "$want" ] && want=allow
    case "$want" in allow|deny) ;; *) return 0 ;; esac

    now=$(dumpsys package "$PKG" 2>/dev/null \
          | sed -n 's/.*android.permission.POST_NOTIFICATIONS: granted=\([a-z]*\).*/\1/p' | head -1)
    # 拿不到说明这个 ROM/版本没有这个运行时权限（Android 12 及以下），什么都不用做。
    [ -z "$now" ] && return 0

    if [ "$want" = "allow" ]; then
        [ "$now" = "true" ] && return 0
        if pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1; then
            log "PERM POST_NOTIFICATIONS -> 已授予（原为 $now）"
        else
            log "PERM POST_NOTIFICATIONS 授予失败"
        fi
    else
        [ "$now" = "false" ] && return 0
        if pm revoke "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1; then
            log "PERM POST_NOTIFICATIONS -> 已撤销（原为 $now）"
        fi
    fi
}

apply_permission_choices() {
    apply_overlay
    apply_bg_popup
    apply_battery
    apply_notification
}

# ---------------------------------------------------------------------------
# Silent boot warm start.
#
# Re-asserting the component makes the SYSTEM start the app process (measured ~1s, and
# it works even from the post-force-stop "stopped" state). Retry a few times so a slow
# boot does not leave auto-recording dead until the user next opens the app - and never
# show any UI: this must stay completely invisible.
# ---------------------------------------------------------------------------
warm_start() {
    n=0
    while [ "$n" -lt 6 ]; do
        if app_alive && ! binding_unhealthy; then
            [ "$n" -gt 0 ] && log "warm start: app up and bound after $n retry(ies)"
            return 0
        fi
        n=$((n + 1))
        log "warm start: app not ready (attempt $n), re-asserting accessibility"
        reassert_component
        settings put secure accessibility_enabled 1 >/dev/null 2>&1
        sleep 5
    done
    log "WARN warm start: app still not bound after $n attempts"
    return 1
}

# Merge our component into the colon-separated list, preserving every other entry.
reassert_component() {
    # Trust the read only when the command itself succeeded. "null"/"" are successful
    # reads meaning "nothing enabled yet", which is precisely when we must add
    # ourselves. A failing read means the provider is not up, and then writing would
    # risk clobbering the user's other accessibility services.
    if ! cur=$(get_services) || ! settings_ready; then
        log "SKIP settings provider not answering, leaving enabled list untouched"
        return 1
    fi

    if has_component "$cur"; then
        return 0
    fi
    case "$cur" in
        ""|null) new="$COMPONENT" ;;
        *)       new="$cur:$COMPONENT" ;;
    esac
    if settings put secure enabled_accessibility_services "$new" >/dev/null 2>&1; then
        log "FIX appended component (previous list: ${cur:-<empty>})"
        return 0
    fi
    log "ERROR could not write enabled_accessibility_services"
    return 1
}

# AMS re-evaluates the enabled list when accessibility_enabled toggles. Used when the
# settings already look correct but the binding/process is actually gone.
#
# Rate limited: a rebind momentarily switches accessibility OFF, so doing it on every
# deep check would itself become a source of missed auto-recording.
LAST_REBIND=0
rebind_allowed() {
    now=$(date +%s 2>/dev/null)
    [ -n "$now" ] || return 0
    [ $((now - LAST_REBIND)) -ge 600 ]
}

force_rebind() {
    log "FIX forcing rebind (toggle accessibility_enabled 0->1)"
    settings put secure accessibility_enabled 0 >/dev/null 2>&1
    sleep 1
    settings put secure accessibility_enabled 1 >/dev/null 2>&1
    LAST_REBIND=$(date +%s 2>/dev/null)
}

# ---------------------------------------------------------------------------
# single instance
# ---------------------------------------------------------------------------
if [ -f "$DISABLE_FLAG" ]; then
    log "disabled via $DISABLE_FLAG, not starting"
    exit 0
fi

# Single instance.
#
# The pid alone is NOT enough. /data/adb survives reboots and pids are recycled from low
# numbers, so after a reboot a stale pidfile very often points at some unrelated process.
# A bare `kill -0` would then succeed and the guard would exit SILENTLY - keep-alive dead
# until someone noticed. So confirm the pid really is another guard before standing down.
is_running_guard() {
    [ -n "$1" ] || return 1
    kill -0 "$1" 2>/dev/null || return 1
    cmd=$(tr '\0' ' ' </proc/"$1"/cmdline 2>/dev/null)
    case "$cmd" in
        *tally_a11y_guard*) return 0 ;;
        *) return 1 ;;
    esac
}

if [ -f "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null)
    if is_running_guard "$old"; then
        exit 0
    fi
    log "stale pidfile (pid ${old:-?} is not a guard); taking over"
fi
echo $$ >"$PIDFILE"

log "guard started (pid=$$, poll=${POLL_SECONDS}s)"

# ---------------------------------------------------------------------------
# Wait for the system to be usable. Magisk runs service.sh at late_start, which
# can precede both boot completion and SettingsProvider. Acting too early used to
# log a bogus "could not write" error and risk a blind overwrite.
# ---------------------------------------------------------------------------
if ! wait_for_boot; then
    log "WARN sys.boot_completed not set after waiting; continuing"
fi
if ! wait_for_settings; then
    log "WARN settings provider still not answering; continuing"
fi

# ---------------------------------------------------------------------------
# boot-time, idempotent power settings. Read-only queries are not tampered with:
# these are the real whitelist switches, unlike hooking isPowerSaveWhitelistApp.
# ---------------------------------------------------------------------------
# Only RUN_ANY_IN_BACKGROUND is unconditional: it is not user-facing, it just keeps
# Android from fencing the app out of the background. The user-facing permissions follow
# the in-app switches instead.
cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1

# immediate first pass
reassert_component
is_enabled || settings put secure accessibility_enabled 1 >/dev/null 2>&1

# Apply whatever the user chose in 保活设置 -> 自动化权限.
apply_permission_choices

# Make sure the app is actually up and bound, silently.
warm_start

# ---------------------------------------------------------------------------
# main loop
# ---------------------------------------------------------------------------
i=0
while true; do
    sleep "$POLL_SECONDS"
    i=$((i + 1))

    if [ -f "$DISABLE_FLAG" ]; then
        log "disabled via flag, stopping"
        rm -f "$PIDFILE"
        exit 0
    fi

    # Safety net for "the app removed the module but the kill did not land": with the
    # script file gone there is nothing left to supervise, so stop. Cheap enough (a
    # single stat, no process spawn) to check on every poll.
    if [ ! -f "$0" ]; then
        log "own script file is gone (module removed); exiting"
        rm -f "$PIDFILE"
        exit 0
    fi

    # Self-cleanup. Root access must not outlive the app that asked for it: if the
    # app has been uninstalled, remove this module and stop. Checked only on the
    # deep-check cadence because `pm list packages` is comparatively expensive.
    if [ $((i % DEEP_CHECK_EVERY)) -eq 0 ]; then
        if ! pm list packages 2>/dev/null | grep -q "^package:$PKG$"; then
            log "app $PKG is no longer installed; removing module and exiting"
            touch "$MODULE_DIR/remove" 2>/dev/null
            rm -f "$PIDFILE"
            rm -rf "$MODULE_DIR" 2>/dev/null
            exit 0
        fi
    fi

    if ! has_component "$(get_services)"; then
        log "DETECTED component missing from enabled list"
        reassert_component
        settings put secure accessibility_enabled 1 >/dev/null 2>&1

    elif ! is_enabled; then
        log "DETECTED accessibility_enabled != 1"
        settings put secure accessibility_enabled 1 >/dev/null 2>&1

    elif ! app_alive; then
        log "DETECTED process gone while settings look correct"
        if rebind_allowed; then force_rebind; fi

    elif [ $((i % BIND_CHECK_EVERY)) -eq 0 ] && binding_unhealthy; then
        log "DETECTED unhealthy accessibility binding: $binding_reason"
        if rebind_allowed; then force_rebind; fi
    fi

    [ $((i % AUTOMATION_EVERY)) -eq 0 ] && apply_permission_choices
    [ $((i % DEEP_CHECK_EVERY)) -eq 0 ] && rotate_log
done
