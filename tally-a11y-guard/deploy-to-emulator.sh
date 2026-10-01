#!/bin/sh
# 把保活模块 + init 开机钩子 + Tally APK 部署到官方模拟器，方便改完代码立刻重测。
#
# 用法：  sh deploy-to-emulator.sh [serial]
# 默认：  emulator-5556
#
# 说明：这台模拟器没有 Magisk，所以用 /system/etc/init/tally_guard.rc 让 init 托管
# 同一个 bin/guard.sh —— 被测的守护逻辑与真机完全一致，只是"谁拉起它"不同。
set -e
SERIAL="${1:-emulator-5556}"
HERE=$(cd "$(dirname "$0")" && pwd)
APK="$HERE/../app/build/outputs/apk/debug/app-debug.apk"

echo "==> 设备 $SERIAL"
adb -s "$SERIAL" root >/dev/null 2>&1 || true
sleep 4
adb -s "$SERIAL" wait-for-device
adb -s "$SERIAL" remount >/dev/null 2>&1 || true
sleep 2

echo "==> 安装 APK"
[ -f "$APK" ] && adb -s "$SERIAL" install -r "$APK" | tail -1

echo "==> 部署模块到 /data/adb/modules/tally_a11y_guard"
for f in module.prop service.sh bin/guard.sh; do
    adb -s "$SERIAL" push "$HERE/$f" "/data/local/tmp/$(basename "$f")" >/dev/null
done
adb -s "$SERIAL" shell "mkdir -p /data/adb/modules/tally_a11y_guard/bin
cp /data/local/tmp/module.prop /data/adb/modules/tally_a11y_guard/
cp /data/local/tmp/service.sh  /data/adb/modules/tally_a11y_guard/
cp /data/local/tmp/guard.sh    /data/adb/modules/tally_a11y_guard/bin/
chmod 755 /data/adb/modules/tally_a11y_guard/service.sh /data/adb/modules/tally_a11y_guard/bin/guard.sh"

echo "==> 写 init 开机钩子（必须 644，否则 init 会 'Skipping insecure file'）"
adb -s "$SERIAL" shell "cat > /system/etc/init/tally_guard.rc <<'RC'
service tally_guard /system/bin/sh /data/adb/modules/tally_a11y_guard/bin/guard.sh
    class late_start
    user root
    group root
    seclabel u:r:su:s0
RC
chmod 644 /system/etc/init/tally_guard.rc
chown root:root /system/etc/init/tally_guard.rc
restorecon /system/etc/init/tally_guard.rc 2>/dev/null || true"

echo "==> 重启以验证开机自启"
adb -s "$SERIAL" reboot
sleep 45
adb -s "$SERIAL" wait-for-device
i=0
while [ $i -lt 20 ]; do
    [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 6; i=$((i+1))
done
sleep 30
adb -s "$SERIAL" root >/dev/null 2>&1 || true
sleep 3
echo "==> 守护状态"
adb -s "$SERIAL" shell "ps -A -o PID,PPID,ETIME,ARGS | grep [g]uard.sh" || true
echo "==> 守护日志"
adb -s "$SERIAL" shell "cat /data/adb/tally-a11y-guard/guard.log 2>/dev/null" || true
