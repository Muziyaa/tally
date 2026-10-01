# Tally A11y Guard — Magisk 模块

让 Tally 的**无障碍服务**在 ROM 清理后台后自动恢复绑定，从而保证自动记账不漏单。

**只改 root 侧，不修改 App 任何代码。**

---

## 它解决什么问题

Tally 靠 `AccessibilityService`（`com.google.android.accessibility.selecttospeak.SelectToSpeakService`）读屏记账。

关键事实（已在 Android 12 设备上实测）：

| 现象 | 实测结果 |
|---|---|
| 无障碍**已绑定**时 `am kill` | **杀不死**，10 秒后 pid 不变 —— 系统自己保护进程 |
| 无障碍**被摘掉**后 | `Bound services:{}`，随即 `am force-stop` **立刻成功** |
| 重新写 `enabled_accessibility_services` | **1 秒内系统把进程拉起来**，服务重新绑定 |
| 从 `stopped=true`（被强制停止后）重写 | **同样 1 秒复活** |

所以故障链是：**绑定被摘 → 进程失去保护 → 被杀 → 漏单**。
而 `AccessibilityManagerService` 对 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 注册了 `ContentObserver`，写入它会触发 `bindService` —— **由系统自己启动 Tally**。

本模块要做的就一件事：**发现绑定没了就重新写这个设置**。

---

## 为什么不 hook system_server（对比上一版 TallyKeeper）

| | 上一版（LSPosed/方案B） | 本模块（方案A） |
|---|---|---|
| 运行位置 | 注入 system_server | root shell |
| bootloop 风险 | 有 | 无 |
| 依赖反射 | 是，跨版本静默失效 | 无 |
| 进程优先级 | `curAdj=0`（耗电、不稳） | **完全不碰** |
| 连强制停止都能穿透 | 需专门 hook | **天然支持**（实测） |
| 调试 | 困难 | `adb` 直接看日志 |

因为重写设置这个原语**连 force-stop 都能穿透**，LSPosed 层没有必要。

---

## 设计取舍（请勿"优化"掉）

- **轮询而非 inotify**：inotify 在部分内核/模拟器上不可用（实测 MuMu 全部路径返回 `Invalid argument`），而一次 `settings get` 成本 <10ms。30 秒轮询 ≈ 2880 次/天 ≈ 十几秒 CPU/天，**耗电可忽略**。
- **绝不碰 `oom_adj` / `oom_score_adj`**：抬高进程优先级是经典错误，会让 lmkd 失衡、待机耗电上升、系统不稳。
- **无 wakelock、无前台服务、无忙等**。
- **合并而非覆盖** `enabled_accessibility_services`：写入时保留用户其他无障碍服务（TalkBack、李跳跳等）。这是本模块最容易出事故的地方。
- 白名单用**真实开关**（`dumpsys deviceidle whitelist +pkg`、`appops RUN_ANY_IN_BACKGROUND`），而不是去 hook `isPowerSaveWhitelistApp`（后者只是查询接口，hook 它不影响真实 Doze 决策）。

---

## 判定逻辑（三重 + 定期深检）

每 30 秒：

1. 组件是否还在 `enabled_accessibility_services` 里？ → 不在则**追加**并置 `accessibility_enabled=1`
2. `accessibility_enabled` 是否为 `1`？ → 否则置 1
3. 进程是否存活（`pidof`）？ → 不存活则**强制重绑**（toggle `accessibility_enabled` 0→1）
4. 每 10 轮（~5 分钟）深检 `dumpsys accessibility`：`Bound services:{}`（什么都没绑上）
   或 `Crashed services` 非空 → 强制重绑

第 3、4 条用于覆盖「安全设置看起来正常、但服务实际已解绑」的 ROM 内部路径。

### 「无障碍未开启时自动开启」

第 1 条本身就是这个能力：当 `enabled_accessibility_services` 是 `null`（从未开启过）
或被人为清空时，守护会把自己的组件写进去并置 `accessibility_enabled=1`，AMS 随即绑定，
**系统会自己把这个 App 的进程拉起来**。实测（Android 12）：

| 时刻 | enabled_accessibility_services | accessibility_enabled | Bound services |
|---|---|---|---|
| 起点（完全关闭） | `null` | `0` | `{}` |
| 守护启动 10s 后 | `com.example.budgetapp/...SelectToSpeakService` | `1` | 我们的服务已绑定，进程已拉起 |

**这里有个容易踩的坑**（已修）：早期版本用「`settings get` 的返回值是不是 `0`/`1`」来判断
SettingsProvider 是否就绪。但从未设置过的键会返回 **`null`**，于是被判成「provider 没就绪」→
**永远跳过所有修复**，表现就是「无障碍关着也不会自动开」。现在改为看
**`settings get` 命令本身是否成功**：成功即视为可信任读取，`null`/空 都表示「还没开 → 去开」。

**重绑做了限流**（同一问题 10 分钟内最多重绑一次）：重绑会 toggle
`accessibility_enabled` 0→1，这一瞬间会**断开设备上所有无障碍服务**，做太频繁本身就会造成漏记。

### Android 13+ 的「受限设置」

侧载安装的应用在 Android 13+ 上默认被「受限设置」挡住，无障碍开关是灰的，需要
**应用信息 → 右上角菜单 → 允许受限设置**（见 [ESET KB8366](https://support.eset.com/de/kb8366-accessibility-restriction-on-android-13-for-apps-installed-from-apk-file)）。
Root 保活模式是**直接写系统设置**来开启的，绕过了这个 UI 限制——App 的保活页在检测到
「Android 13+ 且无障碍未开启」时会显示对应提示，并给一个「打开系统无障碍设置」的入口作为手动兜底。

### 「无障碍开着却记不上账」：必须校验真的绑上了

设置里写着启用、`accessibility_enabled=1`，**不等于服务真的绑上了**。ROM 可以悄悄摘掉绑定而
设置看起来完全正常 —— 这时自动记账静默失效，用户毫无察觉。

所以判定第 4 条会**逐个确认我们自己的服务在不在绑定列表里**，而不只是看列表非空。

**两个实测踩到的坑（都在 v1.4.0 修掉）**

1. **`Bound services` 块会跨行。** 只有一个服务时它是一行，**一旦有第二个服务就会折行**：

   ```
   Bound services:{Service[label=BstCommandProcessor, ...],
                   Service[label=记账屏幕同步助手, ...]}
   ```

   只匹配 `Bound services:` 那一行，会永远看不见第二个服务 —— 实测在 Android 13 上导致
   「明明绑上了却报未绑定」。现在改为提取**整个块**再匹配。

2. **匹配用的是服务 label。** AMS 在 `Bound services` 里只打印 label（没有包名和类名），
   所以只能靠它。为防止 label 被改名后误判，判定「未绑定」时会再用
   `dumpsys activity services <pkg>` 里的 `hasBound=true` **交叉确认**才动手 ——
   因为每次重绑都会**短暂断开设备上所有无障碍服务**，误判的代价很高。

### 关于 Android 13+ 的「受限设置」

侧载应用在 Android 13+ 上，系统设置的开关会变灰，需要「应用信息 → 菜单 → 允许受限设置」。
实测（Android 13 / SDK 33）：**用 root 直接写安全设置可以正常绑定，不受这个 UI 限制影响**，
`dumpsys activity services` 里 `hasBound=true`。所以本方案在 Android 13+ 上依然成立。
（`ACCESS_RESTRICTED_SETTINGS` 这个 appop 也确实存在且可设，实测并非必需。）

### 权限自动化（保活设置 → 自动化权限）

三项权限各可选 **自动开启 / 自动关闭 / 不干预**（默认不干预 = 完全不碰）：

| 权限 | 实现方式 |
|---|---|
| 显示悬浮窗 | `appops set <pkg> SYSTEM_ALERT_WINDOW allow\|deny` |
| 后台弹出界面 | HyperOS/MIUI 厂商 appop，名字与数字码不稳定，会依次探测<br>`BACKGROUND_START_ACTIVITY` / `OP_BACKGROUND_START_ACTIVITY` / `10021`；<br>ROM 不支持则**只警告一次**并提示手动设置 |
| 电池优化白名单 | `dumpsys deviceidle whitelist +<pkg>` / `-<pkg>` |

选择存在 App 的 `app_prefs`（`auto_perm_overlay` / `auto_perm_bgpopup` / `auto_perm_battery`），
守护以 root 直接读取该文件，**不需要第二套协议**。只在状态真的不同时写入，实测幂等
（连续多轮无重复日志）。

选「显示悬浮窗 = 自动关闭」即进入**静默记账**：没有弹窗，识别到就直接入库，备注带 ` (后台)`；
同时 `MainActivity` 不再提示「开启悬浮窗权限」（也可以在保活设置里单独勾「不再提示」）。

### 开机静默拉起（warm start）

`service.sh` 在 `late_start` 跑起来后，守护会重新写无障碍组件 —— 这会让**系统自己把 App 进程拉起来**，
全程无界面。之后 `warm_start()` 会重试最多 6 次（每次间隔 5s）确认 App 真的起来且服务真的绑上，
避免开机慢一点就"直到用户下次手动打开 App 之前都不记账"。

### 启动时序（实测踩过的坑）

Magisk 在 `late_start` 阶段就执行 `service.sh`，**此时 SettingsProvider 可能还不存在**：所有 `settings get` 都返回空。
早期版本把「读不到」误判成「组件丢失」，于是尝试盲写并报 `ERROR could not write`。

现在启动时会：
1. 等 `sys.boot_completed=1`（最多 180s）
2. 等 SettingsProvider 能正常应答（最多 120s）
3. 只有能**信任读取结果**时才允许写入 —— 避免把读失败当成组件缺失而盲写覆盖列表

这保证了开机后日志干净（只有一行 `guard started`），不会产生多余写入。

---

## 安装

**推荐：由 App 自己安装（无需刷模块、无需重启）**

App 内置了这三个文件（`app/src/main/assets/keepalive/`），用户首次启动选择「Root 保活模式」后，
App 会请求 Root 并把模块写到 `/data/adb/modules/tally_a11y_guard/`，**立即生效**。
之后可在「设置 → 保活设置」里随时启用 / 移除。

> 本目录是**唯一事实来源**。改动后请运行 `./sync-to-app.sh` 同步到 App 资源，
> 不要手改 `app/src/main/assets/keepalive/` 下的副本。

**备用：手动刷入独立 zip**（给不想让 App 碰 Root 的用户）

```bash
# 1. 打包
cd tally-a11y-guard
./build.sh

# 2. 刷入：Magisk / KernelSU App → 模块 → 从本地安装 → 选 tally-a11y-guard-v1.1.0.zip → 重启
```

开发期想跳过打包、立即生效：

```bash
adb push tally-a11y-guard /data/local/tmp/
adb shell su -c 'mkdir -p /data/adb/modules/tally_a11y_guard && cp -r /data/local/tmp/tally-a11y-guard/. /data/adb/modules/tally_a11y_guard/'
adb shell su -c 'sh /data/adb/modules/tally_a11y_guard/service.sh'
```

### 安全设计：Root 权限不会比 App 活得更久

守护每隔 10 个轮询周期（生产配置约 5 分钟）检查一次 App 是否还装着，
**一旦发现 App 已被卸载就自删模块并退出**：

```
2026-09-30 20:21:49 app com.example.budgetapp is no longer installed; removing module and exiting
```

App 内的「移除保活模块」会停止守护并删除模块目录。

停止守护**只按 pidfile 里的 PID**（带 `-9` 兜底），**不用 `pkill -f`**。原因是一个踩过的坑：

> `pkill -f <模式>` 会拿模式去匹配**所有进程的完整命令行** —— 包括正在执行这条命令的
> `su` 进程自己（它的 argv 里就含这段脚本文本）。结果是这条命令**把自己杀掉**，
> 调用方只看到一个「退出码非 0 且没有任何输出」的空失败，极难排查。
> 加 `$` 锚定或用 `[g]` 字符类都救不了，因为 `cp` 那几行本身就把脚本路径原样写在了命令行里。

作为兜底，守护每轮都会检查**自己的脚本文件是否还在**，不在就自行退出（单次 `stat`，无进程开销）：

```
2026-09-30 21:01:23 own script file is gone (module removed); exiting
```

所以即使 kill 没打中，`remove()` 删掉模块目录后守护也会在一个轮询周期内自停。

---

## 验证

```bash
# 守护是否在跑
adb shell su -c 'cat /data/adb/tally-a11y-guard/guard.pid; ps -A | grep guard.sh'

# 日志
adb shell su -c 'cat /data/adb/tally-a11y-guard/guard.log'

# 当前状态
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure accessibility_enabled
adb shell pidof com.example.budgetapp
```

**故障注入测试**（模拟 ROM 摘掉无障碍）：

```bash
adb shell settings put secure enabled_accessibility_services null
adb shell settings put secure accessibility_enabled 0
adb shell am force-stop com.example.budgetapp
# 等 ≤35 秒，应当自动恢复：
adb shell pidof com.example.budgetapp
adb shell settings get secure enabled_accessibility_services
adb shell su -c 'tail -5 /data/adb/tally-a11y-guard/guard.log'
```

---

## 关闭 / 卸载

```bash
# 临时停止（保留文件）
adb shell su -c 'touch /data/adb/tally-a11y-guard.disabled'
adb shell su -c 'kill $(cat /data/adb/tally-a11y-guard/guard.pid)'

# 恢复
adb shell su -c 'rm /data/adb/tally-a11y-guard.disabled'
adb shell su -c 'sh /data/adb/modules/tally_a11y_guard/service.sh'
```

在 Magisk App 里移除模块即可，`uninstall.sh` 会停掉守护进程；日志目录会保留以便排查。

---

## 已知未知：HyperOS 的验证缺口

本模块的**机制**已在模拟器上验证，但有一个问题**只能在小米真机上确认**：

> HyperOS 摘掉无障碍，是走 `enabled_accessibility_services`（→ 本模块立刻发现并对症），
> 还是走 ROM 内部路径（→ 设置没变但服务被解绑）？

在无障碍失效的瞬间执行：

```bash
adb shell settings get secure enabled_accessibility_services
```

- **变空 / 不含 Tally** → 走安全设置，本模块完美对症。
- **仍含 Tally 但服务已断** → 走内部路径；此时依赖第 3、4 条判定（进程存活 / 深检）。
  若 HyperOS 存在**反复摘除**的对抗（我们写、它摘 → 抖动），需叠加 ROM 层面的补丁，
  社区已有专门方案：`hyperos-accessibility-fix`。
