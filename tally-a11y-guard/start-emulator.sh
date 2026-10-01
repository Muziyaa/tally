#!/bin/sh
# 启动用于验证 Tally 保活/自动记账的官方 Android 模拟器（带界面）。
#
# 为什么用官方模拟器：它是唯一同时满足「稳定 + 有 root + 系统盘可写」的 macOS 方案，
# 因而可以真正验证「开机自启」——MuMu 会卡死，BlueStacks Air 的 root 是服务端门控的。
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
AVD="${1:-tallyui}"
exec "$SDK/emulator/emulator" -avd "$AVD" \
    -writable-system -selinux permissive \
    -gpu swiftshader_indirect -no-boot-anim \
    -port 5556
