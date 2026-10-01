package com.example.budgetapp.keepalive;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs, inspects and removes the root guard module.
 *
 * <p>The guard itself is shipped inside the APK as assets (see
 * {@code tally-a11y-guard/sync-to-app.sh} in the repository - the module directory is
 * the single source of truth). Installing therefore means: copy the assets out to a
 * root-readable location, then have {@code su} place them under
 * {@code /data/adb/modules/} and start them.
 *
 * <p>A Magisk/KernelSU module directory is used rather than a bare
 * {@code service.d} script so the guard shows up in the root manager's module list and
 * can be removed from there as well as from this app.
 *
 * <p>Every method that talks to {@code su} must be called off the main thread.
 */
public final class GuardInstaller {

    private static final String TAG = "GuardInstaller";

    public static final String MODULE_ID = "tally_a11y_guard";
    public static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;
    public static final String STATE_DIR = "/data/adb/tally-a11y-guard";
    private static final String ASSET_DIR = "keepalive";

    /** Marks the module for removal by the root manager on next boot. */
    private static final String MODULE_DISABLE = MODULE_DIR + "/disable";
    private static final String MODULE_REMOVE = MODULE_DIR + "/remove";

    private GuardInstaller() {
    }

    /** Snapshot of what is currently on disk. */
    public static final class Status {
        public final boolean installed;
        public final boolean running;
        public final String pid;
        public final boolean disabled;

        Status(boolean installed, boolean running, String pid, boolean disabled) {
            this.installed = installed;
            this.running = running;
            this.pid = pid;
            this.disabled = disabled;
        }

        static Status unknown() {
            return new Status(false, false, "", false);
        }
    }

    /**
     * Copies the embedded guard files into app-private storage, where root can read
     * them. Re-extracted on every install so an app update always ships a matching
     * guard.
     */
    public static File extractAssets(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), ASSET_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建缓存目录: " + dir);
        }
        copyAsset(context, "module.prop", new File(dir, "module.prop"));
        copyAsset(context, "service.sh", new File(dir, "service.sh"));
        copyAsset(context, "guard.sh", new File(dir, "guard.sh"));
        return dir;
    }

    private static void copyAsset(Context context, String name, File target)
            throws IOException {
        try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + name);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    /**
     * Installs (or updates) the guard and starts it immediately - no reboot needed.
     *
     * <p>The running guard is stopped before its file is overwritten: {@code sh} reads
     * its script incrementally, so rewriting the file under a live process can corrupt
     * execution.
     */
    public static RootShell.Result install(Context context) {
        File cache;
        try {
            cache = extractAssets(context);
        } catch (IOException e) {
            Log.e(TAG, "extractAssets failed", e);
            return null;
        }

        String base = cache.getAbsolutePath();
        String cmd = ""
                + stopGuardSnippet()
                + "mkdir -p " + MODULE_DIR + "/bin || exit 10; "
                + "cp '" + base + "/module.prop' " + MODULE_DIR + "/module.prop || exit 11; "
                + "cp '" + base + "/service.sh' " + MODULE_DIR + "/service.sh || exit 12; "
                + "cp '" + base + "/guard.sh' " + MODULE_DIR + "/bin/guard.sh || exit 13; "
                + "chmod 755 " + MODULE_DIR + "/service.sh " + MODULE_DIR + "/bin/guard.sh || exit 14; "
                + "chmod 644 " + MODULE_DIR + "/module.prop || exit 15; "
                + "rm -f " + MODULE_DISABLE + " " + MODULE_REMOVE + " 2>/dev/null; "
                + "sh " + MODULE_DIR + "/service.sh || exit 16; "
                + "echo GUARD_INSTALL_OK";

        return RootShell.run(cmd, RootShell.DEFAULT_TIMEOUT_MS);
    }

    /**
     * Shell that stops the running guard.
     *
     * <p>Deliberately PID-based: no {@code pkill -f}, because that pattern would also
     * appear in the command line of the very {@code su} process running this snippet,
     * so it would kill itself and the caller would see an empty, unexplained failure.
     * (Anchoring or bracket tricks do not help either - the {@code cp} lines below
     * contain the script path verbatim.)
     *
     * <p>If the kill is ever missed, the guard still stops on its own: it exits as soon
     * as its own script file disappears, which {@link #remove()} guarantees by deleting
     * the module directory.
     */
    private static String stopGuardSnippet() {
        return ""
                + "if [ -f " + STATE_DIR + "/guard.pid ]; then "
                + "  gpid=\"$(cat " + STATE_DIR + "/guard.pid 2>/dev/null)\"; "
                + "  if [ -n \"$gpid\" ]; then "
                + "    kill \"$gpid\" 2>/dev/null; "
                + "    sleep 1; "
                + "    kill -9 \"$gpid\" 2>/dev/null; "
                + "  fi; "
                + "fi; ";
    }

    /** Stops the guard and removes the module directory. */
    public static RootShell.Result remove() {
        String cmd = ""
                + stopGuardSnippet()
                + "rm -f /data/adb/tally-a11y-guard.disabled 2>/dev/null; "
                + "touch " + MODULE_REMOVE + " 2>/dev/null; "
                + "rm -rf " + MODULE_DIR + " || exit 20; "
                + "rm -rf " + STATE_DIR + " 2>/dev/null; "
                + "echo GUARD_REMOVE_OK";

        return RootShell.run(cmd, RootShell.DEFAULT_TIMEOUT_MS);
    }

    /** Reads install/run state in a single {@code su} round trip. */
    /**
     * Applies the user's 自动化权限 choices immediately, instead of waiting for the
     * guard's next poll (up to 60s).
     *
     * <p>The guard is still the source of truth: it re-applies these on every boot and
     * periodically, so the choice survives a module reinstall or a settings wipe. This
     * method exists purely so the settings page can show what actually happened - most
     * usefully, whether this ROM exposes a settable "后台弹出界面" appop at all.
     */
    public static RootShell.Result applyPermissionChoices(Context context) {
        String pkg = context.getPackageName();
        String overlay = PermissionAutomation.getOverlay(context);
        String bgPopup = PermissionAutomation.getBgPopup(context);
        String battery = PermissionAutomation.getBattery(context);

        StringBuilder cmd = new StringBuilder();
        cmd.append("PKG=").append(pkg).append("; ");

        if (PermissionAutomation.IGNORE.equals(overlay)) {
            cmd.append("echo 'OVERLAY=ignore'; ");
        } else {
            cmd.append("if cmd appops set $PKG SYSTEM_ALERT_WINDOW ").append(overlay)
                    .append(" >/dev/null 2>&1; then echo 'OVERLAY=").append(overlay)
                    .append("'; else echo 'OVERLAY=failed'; fi; ");
        }

        if (PermissionAutomation.IGNORE.equals(bgPopup)) {
            cmd.append("echo 'BGPOPUP=ignore'; ");
        } else {
            // The vendor appop for "后台弹出界面" is spelled differently across HyperOS
            // and MIUI builds, so probe the known spellings and numeric code.
            cmd.append("for c in BACKGROUND_START_ACTIVITY OP_BACKGROUND_START_ACTIVITY 10021; do ")
                    .append("if cmd appops set $PKG $c ").append(bgPopup)
                    .append(" >/dev/null 2>&1; then echo \"BGPOPUP=").append(bgPopup)
                    .append(" via $c\"; break; fi; done; ")
                    .append("echo 'BGPOPUP=probe-finished'; ");
        }

        if (PermissionAutomation.ALLOW.equals(battery)) {
            cmd.append("dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 && echo 'BATTERY=whitelisted'; ");
        } else if (PermissionAutomation.DENY.equals(battery)) {
            cmd.append("dumpsys deviceidle whitelist -$PKG >/dev/null 2>&1 && echo 'BATTERY=removed'; ");
        } else {
            cmd.append("echo 'BATTERY=ignore'; ");
        }

        cmd.append("echo PERM_APPLY_DONE");
        return RootShell.run(cmd.toString(), RootShell.PROBE_TIMEOUT_MS);
    }

    public static Status readStatus() {
        String cmd = ""
                + "if [ -f " + MODULE_DIR + "/module.prop ]; then echo INSTALLED=1; "
                + "else echo INSTALLED=0; fi; "
                + "if [ -f " + MODULE_DISABLE + " ]; then echo DISABLED=1; "
                + "else echo DISABLED=0; fi; "
                + "if [ -f " + STATE_DIR + "/guard.pid ]; then "
                + "  p=\"$(cat " + STATE_DIR + "/guard.pid 2>/dev/null)\"; "
                // /data/adb 跨重启保留、pid 会被回收，所以 kill -0 成功不代表那是守护；
                // 必须核对 cmdline，否则会把"早就死掉的守护"误报成运行中，
                // 修复③的自动拉起也就永远不会触发。
                + "  cmd=\"$(tr '\\0' ' ' </proc/$p/cmdline 2>/dev/null)\"; "
                + "  case \"$cmd\" in "
                + "    *tally_a11y_guard*) echo RUNNING=1; echo PID=$p ;; "
                + "    *) echo RUNNING=0 ;; "
                + "  esac; "
                + "else echo RUNNING=0; fi";

        RootShell.Result result = RootShell.run(cmd, RootShell.PROBE_TIMEOUT_MS);
        if (!result.ok()) {
            return Status.unknown();
        }
        return new Status(
                "1".equals(value(result.stdout, "INSTALLED")),
                "1".equals(value(result.stdout, "RUNNING")),
                value(result.stdout, "PID"),
                "1".equals(value(result.stdout, "DISABLED")));
    }

    /**
     * 守护没在跑就把它拉起来。
     *
     * 为什么需要这个兜底：Magisk 的 service.sh 每次开机只执行一次，守护一旦被杀
     * （激进的后台清理很常见），就没有任何东西会把它拉回来 —— 模块自带的看护进程
     * 万一同时被杀，保活会一直失效到下次重启。App 是用户唯一一定会再打开的东西，
     * 所以由它在启动时补一刀最划算。
     *
     * 只在「已安装 + 未禁用 + 未运行」时才动手，因此是幂等的，不会重复启动。
     *
     * @return true 表示这次真的下发了启动命令
     */
    public static boolean startIfNeeded() {
        Status status = readStatus();
        if (!status.installed || status.disabled || status.running) return false;
        RootShell.Result r = RootShell.run(
                "sh " + MODULE_DIR + "/service.sh >/dev/null 2>&1 &", RootShell.PROBE_TIMEOUT_MS);
        Log.i(TAG, "guard was not running; asked the module to start it (ok=" + r.ok() + ")");
        return r.ok();
    }

    /** Version string of the guard currently on disk, or "" when absent. */
    public static String readInstalledVersion() {
        RootShell.Result result = RootShell.run(
                "[ -f " + MODULE_DIR + "/module.prop ] && sed -n 's/^version=//p' "
                        + MODULE_DIR + "/module.prop || true",
                RootShell.PROBE_TIMEOUT_MS);
        return result.ok() ? result.output() : "";
    }

    private static String value(String output, String key) {
        if (output == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("^" + Pattern.quote(key) + "=(.*)$",
                Pattern.MULTILINE).matcher(output);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
