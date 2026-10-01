package com.example.budgetapp.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.budgetapp.R;
import com.example.budgetapp.keepalive.AccessibilityStatus;
import com.example.budgetapp.keepalive.BatteryOptimizationHelper;
import com.example.budgetapp.keepalive.GuardInstaller;
import com.example.budgetapp.keepalive.KeepAliveMode;
import com.example.budgetapp.keepalive.PermissionAutomation;
import com.example.budgetapp.keepalive.RootDiagnostics;
import com.example.budgetapp.keepalive.RootShell;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lets the user pick a keep-alive mode and, for root mode, shows why root is or is not
 * usable and installs/removes the guard.
 *
 * <p>Every {@code su} call runs on {@link #executor}: the first one blocks on the root
 * manager's permission prompt.
 */
public class KeepAliveSettingsActivity extends AppCompatActivity {
    private androidx.appcompat.widget.SwitchCompat swBgToast;
    private androidx.appcompat.widget.SwitchCompat swSilentRecord;

    private static final String GUARD_LOG_PATH = GuardInstaller.STATE_DIR + "/guard.log";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RadioGroup modeGroup;
    private View groupRoot;
    private View groupNormal;

    private TextView rootStatus;
    private TextView rootSolution;
    private TextView rootExplanation;
    private TextView rootFix;
    private Button rootRedetect;
    private Button copyReport;
    private Button rootInstall;
    private Button rootRemove;

    private TextView batteryStatus;
    private Button batteryRequest;
    private TextView normalGuide;

    private TextView guardStatus;
    private TextView a11yStatus;
    private TextView a11yHint;
    private Spinner spAutoOverlay;
    private Spinner spAutoNotification;
    private Spinner spAutoBgPopup;
    private Spinner spAutoBattery;
    private TextView tvAutoOverlayHint;
    private TextView tvAutoBgPopupHint;
    private TextView tvAutoResult;
    private CheckBox cbNagOverlayDisabled;
    /** Guards the spinners' listeners while {@link #refreshAutomation()} populates them. */
    private boolean automationReady = false;

    private RootDiagnostics.Report lastReport;

    /** Set when we arrived from the first-launch chooser: install once root checks out. */
    private boolean pendingAutoInstall;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keep_alive_settings);

        bindViews();
        wireListeners();

        modeGroup.check(KeepAliveMode.isRootMode(this) ? R.id.rb_mode_root : R.id.rb_mode_normal);
        applyModeVisibility();

        normalGuide.setText(buildOemGuide());
        renderReport(null);

        // Arrived from the first-launch chooser having picked root mode: probe straight
        // away so the user is not left staring at an empty panel.
        if (getIntent() != null
                && getIntent().getBooleanExtra(
                        com.example.budgetapp.keepalive.KeepAliveOnboarding.EXTRA_AUTO_SETUP,
                        false)) {
            pendingAutoInstall = true;
            runDiagnostics();
        }
    }

    private void bindViews() {
        modeGroup = findViewById(R.id.rg_keepalive_mode);
        groupRoot = findViewById(R.id.group_root);
        groupNormal = findViewById(R.id.group_normal);

        rootStatus = findViewById(R.id.tv_root_status);
        rootSolution = findViewById(R.id.tv_root_solution);
        rootExplanation = findViewById(R.id.tv_root_explanation);
        rootFix = findViewById(R.id.tv_root_fix);
        rootRedetect = findViewById(R.id.btn_root_redetect);
        copyReport = findViewById(R.id.btn_copy_report);
        rootInstall = findViewById(R.id.btn_root_install);
        rootRemove = findViewById(R.id.btn_root_remove);

        batteryStatus = findViewById(R.id.tv_battery_status);
        batteryRequest = findViewById(R.id.btn_battery_request);
        normalGuide = findViewById(R.id.tv_normal_guide);

        guardStatus = findViewById(R.id.tv_guard_status);
        a11yStatus = findViewById(R.id.tv_a11y_status);
        a11yHint = findViewById(R.id.tv_a11y_hint);
        spAutoOverlay = findViewById(R.id.sp_auto_overlay);
        spAutoNotification = findViewById(R.id.sp_auto_notification);
        spAutoBgPopup = findViewById(R.id.sp_auto_bg_popup);
        spAutoBattery = findViewById(R.id.sp_auto_battery);
        tvAutoOverlayHint = findViewById(R.id.tv_auto_overlay_hint);
        tvAutoBgPopupHint = findViewById(R.id.tv_auto_bg_popup_hint);
        tvAutoResult = findViewById(R.id.tv_auto_result);
        cbNagOverlayDisabled = findViewById(R.id.cb_nag_overlay_disabled);
        swSilentRecord = findViewById(R.id.sw_silent_record);
        swSilentRecord.setChecked(getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getBoolean("silent_record_enabled", false));
        swSilentRecord.setOnCheckedChangeListener((b, checked) ->
                getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                        .putBoolean("silent_record_enabled", checked).apply());

        // ---- 记账提示方式 ----
        Spinner spNotifyStyle = findViewById(R.id.sp_notify_style);
        TextView tvNotifyHint = findViewById(R.id.tv_notify_style_hint);
        final String[] STYLES = {"notification", "card", "both"};
        final String[] STYLE_LABELS = {"系统通知（推荐，所有页面都能看到）", "顶部小卡片（支付页可能被拦截）", "两者都要"};
        ArrayAdapter<String> styleAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, STYLE_LABELS);
        styleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spNotifyStyle.setAdapter(styleAdapter);
        String curStyle = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getString("record_notify_style", "notification");
        for (int i = 0; i < STYLES.length; i++) {
            if (STYLES[i].equals(curStyle)) { spNotifyStyle.setSelection(i); break; }
        }
        tvNotifyHint.setText("当前：不允许被拦截的页面也能看到 —— 「"
                + STYLE_LABELS[spNotifyStyle.getSelectedItemPosition()] + "」");
        spNotifyStyle.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                        .putString("record_notify_style", STYLES[pos]).apply();
                tvNotifyHint.setText("当前：「" + STYLE_LABELS[pos] + "」");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        // ---- 悬浮窗权限自检 ----
        //
        // 这个检查很必要：系统设置里「允许显示在其他应用上方」看着是开的，
        // 但 App 内部 Settings.canDrawOverlays() 仍可能返回 false —— 卡片就会静默失效。
        // 这里把**内部真实结果**直接显示出来，并给一个一键去授权的入口。
        TextView tvOverlayCheck = findViewById(R.id.tv_overlay_check);
        Runnable refreshOverlayCheck = () -> {
            boolean can = android.provider.Settings.canDrawOverlays(this);
            int mode = -999;
            try {
                android.app.AppOpsManager aom =
                        (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
                mode = aom.unsafeCheckOpNoThrow("android:system_alert_window",
                        android.os.Process.myUid(), getPackageName());
            } catch (Throwable ignored) {}
            String modeText;
            switch (mode) {
                case android.app.AppOpsManager.MODE_ALLOWED: modeText = "允许"; break;
                case android.app.AppOpsManager.MODE_IGNORED: modeText = "拒绝"; break;
                case android.app.AppOpsManager.MODE_ERRORED: modeText = "错误"; break;
                case android.app.AppOpsManager.MODE_DEFAULT: modeText = "默认（=未真正授权）"; break;
                default: modeText = "未知(" + mode + ")";
            }
            tvOverlayCheck.setText("悬浮窗权限自检：canDrawOverlays=" + can + "，系统记录=" + modeText
                    + (can ? "\n✅ 提示卡片可以正常显示"
                           : "\n❌ 卡片显示不出来就是因为这个 —— 请点下面的按钮重新授权"));
        };
        refreshOverlayCheck.run();
        findViewById(R.id.btn_fix_overlay).setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
        });

        findViewById(R.id.btn_test_toast).setOnClickListener(v -> {
            com.google.android.accessibility.selecttospeak.SelectToSpeakService svc =
                    com.google.android.accessibility.selecttospeak.SelectToSpeakService.getInstance();
            if (svc == null) {
                android.widget.Toast.makeText(this,
                        "无障碍服务没在运行，先去开启「记账屏幕同步助手」",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            svc.runToastSelfTest();
        });

        swBgToast = findViewById(R.id.sw_bg_toast);
        swBgToast.setChecked(getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getBoolean("bg_toast_enabled", true));
        swBgToast.setOnCheckedChangeListener((b, checked) ->
                getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                        .putBoolean("bg_toast_enabled", checked).apply());
    }

    private void wireListeners() {
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            KeepAliveMode.set(this,
                    checkedId == R.id.rb_mode_root
                            ? KeepAliveMode.MODE_ROOT
                            : KeepAliveMode.MODE_NORMAL);
            applyModeVisibility();
            // Re-render the run-status card too, otherwise it keeps describing the mode
            // the user just left.
            refreshGuardSection();
        });

        setupAutomationSection();
        rootRedetect.setOnClickListener(v -> runDiagnostics());
        copyReport.setOnClickListener(v -> copyReportToClipboard());
        rootInstall.setOnClickListener(v -> confirmAndInstall());
        rootRemove.setOnClickListener(v -> confirmAndRemove());
        findViewById(R.id.btn_view_log).setOnClickListener(v -> showGuardLog());

        batteryRequest.setOnClickListener(v -> requestBatteryExemption());
        findViewById(R.id.btn_open_a11y_settings).setOnClickListener(v -> {
            try {
                startActivity(AccessibilityStatus.buildAccessibilitySettingsIntent());
            } catch (Exception e) {
                Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_open_app_settings).setOnClickListener(v -> {
            try {
                startActivity(BatteryOptimizationHelper.buildAppDetailsIntent(this));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBatteryStatus();
        refreshAccessibilityStatus();
        refreshAutomation();
        refreshGuardSection();
    }

    /** Renders the run-status card for whichever mode is currently selected. */
    private void refreshGuardSection() {
        if (KeepAliveMode.isRootMode(this)) {
            refreshGuardStatus();
        } else {
            guardStatus.setText("当前为普通模式，未安装守护模块。");
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void applyModeVisibility() {
        boolean root = KeepAliveMode.isRootMode(this);
        groupRoot.setVisibility(root ? View.VISIBLE : View.GONE);
        groupNormal.setVisibility(root ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // root mode
    // ------------------------------------------------------------------

    private void runDiagnostics() {
        setBusy(true, "正在检测 Root…");
        // Application context: this runs on a worker thread, so never hand it the Activity.
        final android.content.Context appContext = getApplicationContext();
        executor.execute(() -> {
            RootDiagnostics.Report report = RootDiagnostics.diagnose(appContext);
            mainHandler.post(() -> {
                lastReport = report;
                renderReport(report);
                setBusy(false, null);
            });
        });
    }

    private void renderReport(RootDiagnostics.Report report) {
        if (report == null) {
            rootStatus.setText("尚未检测");
            rootSolution.setText("");
            rootExplanation.setText("点「重新检测」以确认设备是否可以安装保活守护。");
            rootFix.setText("");
            rootInstall.setEnabled(false);
            return;
        }
        rootStatus.setText(report.title());
        rootSolution.setText("Root 方案：" + report.rootSolution
                + "　|　su 可见：" + (report.suBinaryPresent ? "是" : "否"));
        rootExplanation.setText(report.explanation());
        rootFix.setText(report.howToFix());
        rootInstall.setEnabled(report.isOk());

        if (pendingAutoInstall) {
            pendingAutoInstall = false;
            if (report.isOk()) {
                confirmAndInstall();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("暂时无法使用 Root 保活")
                        .setMessage(report.explanation() + "\n\n" + report.howToFix())
                        .setPositiveButton("知道了", null)
                        .show();
            }
        }
    }

    private void confirmAndInstall() {
        new AlertDialog.Builder(this)
                .setTitle("启用 Root 保活")
                .setMessage("将会执行以下操作：\n\n"
                        + "1. 请求 Root 权限（会弹出授权框）\n"
                        + "2. 安装守护模块到 /data/adb/modules/tally_a11y_guard/\n"
                        + "3. 立即启动守护，无需重启\n\n"
                        + "守护只做一件事：发现无障碍被系统关闭时把它恢复。\n"
                        + "它不修改进程优先级、不持有唤醒锁，并在你卸载 Tally 后自动删除自己。")
                .setPositiveButton("继续", (d, w) -> installGuard())
                .setNegativeButton("取消", null)
                .show();
    }

    private void installGuard() {
        setBusy(true, "正在安装守护模块…");
        executor.execute(() -> {
            RootShell.Result result = GuardInstaller.install(this);
            GuardInstaller.Status status = GuardInstaller.readStatus();
            mainHandler.post(() -> {
                setBusy(false, null);
                renderGuardStatus(status);
                boolean success = result != null && result.ok()
                        && result.output().contains("GUARD_INSTALL_OK");
                if (success) {
                    new AlertDialog.Builder(this)
                            .setTitle("已启用")
                            .setMessage("守护已安装并启动。\n\n"
                                    + "· 现在已生效，不需要重启\n"
                                    + "· 重启后由 Root 管理器自动拉起\n"
                                    + "· 模块也会出现在 Root 管理器的模块列表里，可从那里移除")
                            .setPositiveButton("好", null)
                            .show();
                } else {
                    showFailure("安装失败", result);
                }
            });
        });
    }

    private void confirmAndRemove() {
        new AlertDialog.Builder(this)
                .setTitle("移除保活模块")
                .setMessage("将停止守护并删除 /data/adb/modules/tally_a11y_guard/。\n"
                        + "无障碍服务本身不受影响，仍可在系统设置里使用。")
                .setPositiveButton("移除", (d, w) -> removeGuard())
                .setNegativeButton("取消", null)
                .show();
    }

    private void removeGuard() {
        setBusy(true, "正在移除…");
        executor.execute(() -> {
            RootShell.Result result = GuardInstaller.remove();
            GuardInstaller.Status status = GuardInstaller.readStatus();
            mainHandler.post(() -> {
                setBusy(false, null);
                renderGuardStatus(status);
                if (result != null && result.output().contains("GUARD_REMOVE_OK")) {
                    Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show();
                } else {
                    showFailure("移除失败", result);
                }
            });
        });
    }

    private void refreshGuardStatus() {
        executor.execute(() -> {
            if (!RootShell.suBinaryPresent()) {
                // Do not trigger a permission prompt just for a status refresh.
                mainHandler.post(() -> guardStatus.setText(
                        "未检测到 su，尚未安装守护。点「重新检测」查看原因。"));
                return;
            }
            GuardInstaller.Status status = GuardInstaller.readStatus();
            String version = status.installed ? GuardInstaller.readInstalledVersion() : "";
            mainHandler.post(() -> {
                renderGuardStatus(status);
                if (status.installed && version != null && !version.isEmpty()) {
                    guardStatus.append("\n模块版本：" + version);
                }
            });
        });
    }

    private void renderGuardStatus(GuardInstaller.Status status) {
        StringBuilder sb = new StringBuilder();
        sb.append("守护模块：").append(status.installed ? "已安装" : "未安装");
        if (status.installed) {
            sb.append("\n运行状态：").append(status.running
                    ? "运行中（pid " + status.pid + "）"
                    : "未运行（将在下次开机或重新启用后启动）");
            if (status.disabled) {
                sb.append("\n注意：模块当前被 Root 管理器禁用。");
            }
        }
        guardStatus.setText(sb.toString());
    }

    private void showGuardLog() {
        setBusy(true, "正在读取日志…");
        executor.execute(() -> {
            RootShell.Result result = RootShell.run(
                    "[ -f " + GUARD_LOG_PATH + " ] && tail -n 100 " + GUARD_LOG_PATH
                            + " || echo '（暂无日志，守护可能还没有运行过）'",
                    RootShell.PROBE_TIMEOUT_MS);
            mainHandler.post(() -> {
                setBusy(false, null);
                String text = result.ok() ? result.output() : result.combined();
                new AlertDialog.Builder(this)
                        .setTitle("守护日志")
                        .setMessage(text.isEmpty() ? "（空）" : text)
                        .setPositiveButton("关闭", null)
                        .setNeutralButton("复制", (d, w) -> copyToClipboard("守护日志", text))
                        .show();
            });
        });
    }

    private void copyReportToClipboard() {
        if (lastReport == null) {
            Toast.makeText(this, "请先点「重新检测」", Toast.LENGTH_SHORT).show();
            return;
        }
        copyToClipboard("Tally Root 诊断报告", lastReport.reportText());
        Toast.makeText(this, "诊断报告已复制", Toast.LENGTH_SHORT).show();
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager manager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText(label, text));
        }
    }

    // ------------------------------------------------------------------
    // normal mode
    // ------------------------------------------------------------------

    /**
     * Shows the real accessibility state, read from the public AccessibilityManager API
     * rather than inferred from the guard, so it stays honest when the guard is absent.
     */
    // ------------------------------------------------------------------
    // 自动化权限
    // ------------------------------------------------------------------

    private void setupAutomationSection() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"自动开启", "自动关闭", "不干预"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAutoOverlay.setAdapter(adapter);
        if (spAutoNotification != null) spAutoNotification.setAdapter(adapter);
        spAutoBgPopup.setAdapter(adapter);
        spAutoBattery.setAdapter(adapter);

        AdapterView.OnItemSelectedListener persist = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // setSelection() during refresh also fires this; automationReady keeps the
                // half-populated state from being written back as "auto allow".
                if (!automationReady) {
                    return;
                }
                PermissionAutomation.setOverlay(KeepAliveSettingsActivity.this,
                        PermissionAutomation.CHOICES[spAutoOverlay.getSelectedItemPosition()]);
                PermissionAutomation.setBgPopup(KeepAliveSettingsActivity.this,
                        PermissionAutomation.CHOICES[spAutoBgPopup.getSelectedItemPosition()]);
                PermissionAutomation.setBattery(KeepAliveSettingsActivity.this,
                        PermissionAutomation.CHOICES[spAutoBattery.getSelectedItemPosition()]);
                if (spAutoNotification != null) {
                    PermissionAutomation.setNotification(KeepAliveSettingsActivity.this,
                            PermissionAutomation.CHOICES[spAutoNotification.getSelectedItemPosition()]);
                }
                updateAutomationHints();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        spAutoOverlay.setOnItemSelectedListener(persist);
        spAutoBgPopup.setOnItemSelectedListener(persist);
        spAutoBattery.setOnItemSelectedListener(persist);

        findViewById(R.id.btn_auto_apply).setOnClickListener(v -> applyAutomationNow());
        findViewById(R.id.btn_auto_bg_popup_manual).setOnClickListener(v -> {
            // MIUI/HyperOS keep "后台弹出界面" under 应用信息 -> 权限管理; there is no
            // public deep link, so open the app details page and let the user pick it.
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开应用信息页", Toast.LENGTH_SHORT).show();
            }
        });
        cbNagOverlayDisabled.setOnClickListener(v ->
                PermissionAutomation.setOverlayNagDisabled(this,
                        cbNagOverlayDisabled.isChecked()));
    }

    private void refreshAutomation() {
        automationReady = false;
        if (spAutoNotification != null) {
            spAutoNotification.setSelection(
                    PermissionAutomation.indexOf(PermissionAutomation.getNotification(this)));
        }
        spAutoOverlay.setSelection(
                PermissionAutomation.indexOf(PermissionAutomation.getOverlay(this)));
        spAutoBgPopup.setSelection(
                PermissionAutomation.indexOf(PermissionAutomation.getBgPopup(this)));
        spAutoBattery.setSelection(
                PermissionAutomation.indexOf(PermissionAutomation.getBattery(this)));
        cbNagOverlayDisabled.setChecked(
                PermissionAutomation.isOverlayNagDisabled(this));
        automationReady = true;
        updateAutomationHints();
    }

    private void updateAutomationHints() {
        String overlay = PermissionAutomation.getOverlay(this);
        if (PermissionAutomation.DENY.equals(overlay)) {
            tvAutoOverlayHint.setText("已选择静默记账：不再弹确认框，识别到就直接入库（备注会带“ (后台)”）。");
        } else if (PermissionAutomation.ALLOW.equals(overlay)) {
            tvAutoOverlayHint.setText("保留确认弹窗，记账前可以改金额和分类。");
        } else {
            tvAutoOverlayHint.setText("不干预：保持你手动设置的状态。关闭悬浮窗即为静默记账。");
        }

        String bg = PermissionAutomation.getBgPopup(this);
        if (PermissionAutomation.IGNORE.equals(bg)) {
            tvAutoBgPopupHint.setText("不干预。若曾出现“弹窗不出来”，把它设为自动开启。");
        } else {
            tvAutoBgPopupHint.setText("HyperOS 专有。若本机不支持自动设置，用下面的按钮手动开一次。");
        }
    }

    private void applyAutomationNow() {
        tvAutoResult.setText("正在应用…");
        executor.execute(() -> {
            RootShell.Result result = GuardInstaller.applyPermissionChoices(this);
            String out = result.output().trim();
            mainHandler.post(() -> tvAutoResult.setText(result.ok()
                    ? "已应用：\n" + (out.isEmpty() ? "(无输出)" : out)
                    : "应用失败：" + result.combined()));
        });
    }

    private void refreshAccessibilityStatus() {
        a11yStatus.setText(AccessibilityStatus.describe(this));
        if (AccessibilityStatus.mayBeBlockedByRestrictedSettings(this)) {
            a11yHint.setVisibility(View.VISIBLE);
            a11yHint.setText("系统是 Android 13 及以上。侧载安装的应用默认受「受限设置」限制，"
                    + "无障碍开关可能是灰的：\n"
                    + "应用信息 → 右上角菜单 → 允许受限设置，然后再开启无障碍。\n\n"
                    + "Root 保活模式会直接写入系统设置来开启它，不受这个限制。");
        } else {
            a11yHint.setVisibility(View.GONE);
        }
    }

    private void refreshBatteryStatus() {
        boolean exempt = BatteryOptimizationHelper.isExempt(this);
        batteryStatus.setText(exempt
                ? "已加入白名单：系统不会在深度休眠时冻结 Tally。"
                : "尚未加入白名单：长时间深度休眠后可能被冻结，导致漏记。");
        batteryRequest.setEnabled(!exempt);
        batteryRequest.setText(exempt ? "已在白名单中" : "申请忽略电池优化");
    }

    private void requestBatteryExemption() {
        try {
            startActivity(BatteryOptimizationHelper.buildRequestIntent(this));
        } catch (Exception e) {
            // Some OEM builds intercept this dialog; send the user to app settings instead.
            new AlertDialog.Builder(this)
                    .setTitle("无法直接申请")
                    .setMessage("你的系统没有提供这个授权弹窗。\n"
                            + "请手动在「应用信息 → 电池 → 不受限制」中设置。")
                    .setPositiveButton("打开应用设置", (d, w) -> {
                        try {
                            startActivity(BatteryOptimizationHelper.buildAppDetailsIntent(this));
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    private String buildOemGuide() {
        return "没有公开接口可以自动设置，需要你手动确认：\n\n"
                + "1. 省电策略：设置 → 应用设置 → 应用管理 → Tally → 省电策略 → 选「无限制」\n"
                + "2. 自启动：同一页面打开「自启动」\n"
                + "3. 锁定后台：在最近任务里下拉 Tally 卡片，点锁图标锁定\n"
                + "4. 无障碍：设置 → 更多设置 → 无障碍 → 确认「记账屏幕同步助手」是开启的\n"
                + "5. 如果系统仍会自动关闭无障碍（小米 / 红米常见），"
                + "请改用上面的「Root 保活模式」，它能自动恢复。";
    }

    // ------------------------------------------------------------------

    private void showFailure(String title, RootShell.Result result) {
        String detail = result == null
                ? "无法读取内置的守护文件。"
                : result.combined();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("请确认 Root 权限已授予 Tally。\n\n" + detail)
                .setPositiveButton("复制详情", (d, w) -> copyToClipboard(title, detail))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void setBusy(boolean busy, String message) {
        rootRedetect.setEnabled(!busy);
        copyReport.setEnabled(!busy);
        rootRemove.setEnabled(!busy);
        if (busy) {
            rootInstall.setEnabled(false);
            if (message != null) {
                rootStatus.setText(message);
            }
        } else {
            rootInstall.setEnabled(lastReport != null && lastReport.isOk());
        }
    }
}
