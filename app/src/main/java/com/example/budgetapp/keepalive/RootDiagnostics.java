package com.example.budgetapp.keepalive;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out whether root mode can be used, and - when it cannot - <b>why</b>.
 *
 * <p>A plain "root not detected" message is useless to the user, because the usual
 * causes need completely different fixes:
 * <ul>
 *   <li>the device is not rooted at all;</li>
 *   <li>the device is rooted but this app was never granted superuser access;</li>
 *   <li>the request was denied once and the root manager remembered it, so no prompt
 *       ever appears again;</li>
 *   <li>root hiding (DenyList / Shamiko) made {@code su} invisible to us.</li>
 * </ul>
 * {@link #diagnose(Context)} distinguishes these and hands back the matching
 * instructions.
 *
 * <p>Must be called from a background thread.
 */
public final class RootDiagnostics {

    /**
     * Known root manager packages, used to name the manager in the instructions.
     *
     * <p>Needed because the most common failure - access denied - is exactly the case
     * where the {@code su} probe cannot run, so we would otherwise tell the user to
     * "open the Unknown manager". Readable without any permission of our own.
     */
    private static final String[][] ROOT_MANAGER_PACKAGES = {
            {"io.github.huskydg.magisk", "Magisk (Kitsune)"},
            {"com.topjohnwu.magisk", "Magisk"},
            {"me.weishu.kernelsu", "KernelSU"},
            {"me.bmax.apatch", "APatch"},
            {"com.dergoogler.mmrl", "MMRL"},
    };

    /** What the user (and the UI) needs to act on. */
    public enum Status {
        /** Root works and we can write to /data/adb. */
        OK,
        /** No root manager is installed. */
        NOT_ROOTED,
        /** Root exists but this app is not allowed to use it. */
        PERMISSION_DENIED,
        /** {@code su} was invoked but never answered - typically a prompt nobody tapped. */
        TIMEOUT,
        /** We are root, but /data/adb is not writable, so no module can be installed. */
        DATA_ADB_NOT_WRITABLE,
        /** The probe did not finish for some other reason. */
        ERROR
    }

    /** One probe, one prompt: everything we need comes from a single {@code su} call. */
    private static final String PROBE = ""
            + "echo \"PROBE_UID=$(id -u 2>/dev/null)\"; "
            + "echo \"PROBE_MAGISK=$(command -v magisk 2>/dev/null)\"; "
            + "echo \"PROBE_MAGISK_VER=$(magisk -v 2>/dev/null)\"; "
            + "echo \"PROBE_KSU=$(ls -d /data/adb/ksu 2>/dev/null)\"; "
            + "echo \"PROBE_KSUD=$(command -v ksud 2>/dev/null)\"; "
            + "echo \"PROBE_APATCH=$(ls -d /data/adb/ap 2>/dev/null)\"; "
            + "echo \"PROBE_DATAADB=$( (touch /data/adb/.tally_probe && rm -f /data/adb/.tally_probe)"
            + " >/dev/null 2>&1 && echo rw || echo ro )\"; "
            + "echo \"PROBE_MODULES=$(ls -d /data/adb/modules 2>/dev/null)\"; "
            + "echo \"PROBE_DONE=1\"";

    private RootDiagnostics() {
    }

    /** The full verdict, including ready-to-show explanations. */
    public static final class Report {
        public final Status status;
        /** e.g. "Magisk 26.4-kitsune" / "KernelSU" / "APatch" / "未知". */
        public final String rootSolution;
        /** true when a su binary is visible without asking for root. */
        public final boolean suBinaryPresent;
        public final RootShell.Result rawResult;

        Report(Status status, String rootSolution, boolean suBinaryPresent,
                RootShell.Result rawResult) {
            this.status = status;
            this.rootSolution = rootSolution;
            this.suBinaryPresent = suBinaryPresent;
            this.rawResult = rawResult;
        }

        public boolean isOk() {
            return status == Status.OK;
        }

        /** Short label for the status row. */
        public String title() {
            switch (status) {
                case OK:
                    return "Root 可用";
                case NOT_ROOTED:
                    return "未检测到 Root";
                case PERMISSION_DENIED:
                    return "Root 未授权";
                case TIMEOUT:
                    return "Root 授权超时";
                case DATA_ADB_NOT_WRITABLE:
                    return "/data/adb 不可写";
                default:
                    return "检测失败";
            }
        }

        /** What actually went wrong. */
        public String explanation() {
            switch (status) {
                case OK:
                    return "Root 权限正常，且可以写入 /data/adb，能够安装保活模块。";
                case NOT_ROOTED:
                    return "设备上没有找到可用的 su，也没有检测到 Magisk / KernelSU / APatch，"
                            + "因此无法使用 Root 保活。";
                case PERMISSION_DENIED:
                    return "设备已经 Root（检测到 " + rootSolution + "），但 Tally 没有拿到超级用户授权，"
                            + "所以 su 命令被拒绝了。";
                case TIMEOUT:
                    return "su 命令一直没有返回。通常是授权弹窗没有被处理，"
                            + "或者你之前点过「拒绝」并且被记住了，导致不再弹窗。";
                case DATA_ADB_NOT_WRITABLE:
                    return "Root 权限是正常的，但 /data/adb 无法写入，"
                            + "保活模块没有地方可以安装。";
                default:
                    return "检测过程没有正常结束，请查看下面的原始输出。";
            }
        }

        /** Concrete steps, in the order the user should try them. */
        public String howToFix() {
            switch (status) {
                case OK:
                    return "无需处理。";
                case NOT_ROOTED:
                    return "1. 确认设备已解锁 Bootloader\n"
                            + "2. 安装 Magisk 或 KernelSU 并完成 Root\n"
                            + "3. 如果已经 Root 却仍然看到这条提示，说明 Root 被隐藏了，"
                            + "请看下面「Root 被隐藏」一节";
                case PERMISSION_DENIED:
                    return "1. 打开 " + rootSolution + " 管理器\n"
                            + "2. 进入「超级用户」/「授权管理」\n"
                            + "3. 找到 Tally（com.example.budgetapp），打开开关\n"
                            + "4. 返回本页，点「重新检测」\n"
                            + "（若列表里没有 Tally，请先在下方点一次「启用 Root 保活」把授权弹窗唤出来）";
                case TIMEOUT:
                    return "1. 打开 " + rootSolution + " 管理器 →「超级用户」\n"
                            + "2. 检查 Tally 是否被记录为「拒绝」，如有请删除该记录\n"
                            + "3. 确认没有开启「默认拒绝」之类的静默策略\n"
                            + "4. 返回本页重试；弹窗出现时请及时点「允许」";
                case DATA_ADB_NOT_WRITABLE:
                    return "1. 确认 Root 管理器本身工作正常（能授权其它应用）\n"
                            + "2. 检查 /data 分区是否已满\n"
                            + "3. 检查是否启用了会限制 /data/adb 的模块或 SELinux 策略\n"
                            + "4. 重启设备后重试";
                default:
                    return "请把下面的诊断报告复制反馈，其中包含原始命令输出。";
            }
        }

        /** A copy-pasteable block for bug reports. */
        public String reportText() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Tally Root 诊断报告 ===\n");
            sb.append("结论: ").append(title()).append(" (").append(status).append(")\n");
            sb.append("Root 方案: ").append(rootSolution).append('\n');
            sb.append("su 二进制可见: ").append(suBinaryPresent).append('\n');
            if (rawResult != null) {
                sb.append("退出码: ").append(rawResult.exitCode)
                        .append("  超时: ").append(rawResult.timedOut).append('\n');
                sb.append("--- 原始输出 ---\n").append(rawResult.combined()).append('\n');
            }
            return sb.toString();
        }
    }

    public static Report diagnose(Context context) {
        boolean suVisible = RootShell.suBinaryPresent();
        RootShell.Result result = RootShell.run(PROBE, RootShell.DEFAULT_TIMEOUT_MS);
        String solution = resolveSolution(context, result);
        boolean managerInstalled = !detectRootManagerPackage(context).isEmpty();

        if (result.timedOut) {
            return new Report(Status.TIMEOUT, solution, suVisible, result);
        }

        String out = result.stdout;

        if (!result.ok()) {
            // su ran but refused: either it was denied, or there is no su at all.
            Status status;
            if (suVisible) {
                status = Status.PERMISSION_DENIED;
            } else if (managerInstalled) {
                // A root manager is installed but su is invisible to us, so the request
                // could not even be made - root hiding is the likely cause.
                status = Status.PERMISSION_DENIED;
            } else {
                String lower = result.combined().toLowerCase();
                boolean managerHints = lower.contains("denied")
                        || lower.contains("not allowed")
                        || lower.contains("permission");
                status = managerHints ? Status.PERMISSION_DENIED : Status.NOT_ROOTED;
            }
            return new Report(status, solution, suVisible, result);
        }

        if (!probeValue(out, "PROBE_UID").equals("0")) {
            return new Report(suVisible ? Status.PERMISSION_DENIED : Status.NOT_ROOTED,
                    solution, suVisible, result);
        }

        if (!probeValue(out, "PROBE_DONE").equals("1")) {
            return new Report(Status.ERROR, solution, suVisible, result);
        }

        if (!"rw".equals(probeValue(out, "PROBE_DATAADB"))) {
            return new Report(Status.DATA_ADB_NOT_WRITABLE, solution, suVisible, result);
        }

        return new Report(Status.OK, solution, suVisible, result);
    }

    /** Pulls one {@code PROBE_X=value} line out of the probe output. */
    private static String probeValue(String output, String key) {
        if (output == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("^" + Pattern.quote(key) + "=(.*)$",
                Pattern.MULTILINE).matcher(output);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /**
     * Names the root manager, preferring what the probe could read and falling back to
     * the installed-package list - which is the only source available when access was
     * denied, i.e. exactly when the instructions matter most.
     */
    private static String resolveSolution(Context context, RootShell.Result result) {
        String fromProbe = detectSolutionFromProbe(result);
        if (!fromProbe.isEmpty()) {
            return fromProbe;
        }
        String fromPackage = detectRootManagerPackage(context);
        if (!fromPackage.isEmpty()) {
            return fromPackage;
        }
        return "未知";
    }

    /** Name of an installed root manager, or "" when none of the known ones is present. */
    private static String detectRootManagerPackage(Context context) {
        if (context == null) {
            return "";
        }
        PackageManager packageManager = context.getPackageManager();
        for (String[] entry : ROOT_MANAGER_PACKAGES) {
            try {
                packageManager.getPackageInfo(entry[0], 0);
                return entry[1];
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not this one.
            } catch (Exception ignored) {
                // Defensive: a broken PackageManager must not break diagnosis.
            }
        }
        return "";
    }

    private static String detectSolutionFromProbe(RootShell.Result result) {
        String out = result.stdout;
        String magiskVersion = probeValue(out, "PROBE_MAGISK_VER");
        if (!magiskVersion.isEmpty()) {
            // `magisk -v` can append build metadata ("26.4-kitsune:MAGISK:R"); the part
            // before the colon is what users recognise.
            int colon = magiskVersion.indexOf(':');
            if (colon > 0) {
                magiskVersion = magiskVersion.substring(0, colon);
            }
            return "Magisk " + magiskVersion;
        }
        if (!probeValue(out, "PROBE_KSU").isEmpty() || !probeValue(out, "PROBE_KSUD").isEmpty()) {
            return "KernelSU";
        }
        if (!probeValue(out, "PROBE_APATCH").isEmpty()) {
            return "APatch";
        }
        if (!probeValue(out, "PROBE_MAGISK").isEmpty()) {
            return "Magisk";
        }
        // Nothing from the probe: fall back to a hint in the error text.
        String combined = result.combined().toLowerCase();
        if (combined.contains("magisk")) {
            return "Magisk";
        }
        if (combined.contains("kernelsu") || combined.contains("ksu")) {
            return "KernelSU";
        }
        if (combined.contains("apatch")) {
            return "APatch";
        }
        return "";
    }
}
