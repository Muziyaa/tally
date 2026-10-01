package com.example.budgetapp.keepalive;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;
import android.accessibilityservice.AccessibilityServiceInfo;

/**
 * Read-only view of whether Tally's accessibility service is switched on.
 *
 * <p>Uses the public {@link AccessibilityManager} API, so it needs no root and works
 * even when the guard is not installed. That makes it a genuine second opinion: the
 * user can see the accessibility state for themselves instead of taking the guard's
 * word for it, and the keep-alive page can tell "keep-alive is fine" apart from
 * "accessibility was never switched on in the first place".
 */
public final class AccessibilityStatus {

    /** Must match the {@code android:name} of the service in AndroidManifest.xml. */
    public static final String SERVICE_CLASS =
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService";

    private AccessibilityStatus() {
    }

    private static AccessibilityManager manager(Context context) {
        return (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    /** Whether the accessibility master switch is on at all. */
    public static boolean isAccessibilityEnabled(Context context) {
        AccessibilityManager manager = manager(context);
        return manager != null && manager.isEnabled();
    }

    /** Whether Tally's own service appears in the system's enabled-service list. */
    public static boolean isOurServiceEnabled(Context context) {
        AccessibilityManager manager = manager(context);
        if (manager == null) {
            return false;
        }
        List<AccessibilityServiceInfo> enabled;
        try {
            enabled = manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        } catch (Exception e) {
            return false;
        }
        if (enabled == null) {
            return false;
        }

        ComponentName ours = new ComponentName(context.getPackageName(), SERVICE_CLASS);
        for (AccessibilityServiceInfo info : enabled) {
            if (info == null || info.getResolveInfo() == null) {
                continue;
            }
            ServiceInfo serviceInfo = info.getResolveInfo().serviceInfo;
            if (serviceInfo == null) {
                continue;
            }
            if (ours.equals(new ComponentName(serviceInfo.packageName, serviceInfo.name))) {
                return true;
            }
        }
        return false;
    }

    /** Where the user can switch the service on by hand. */
    public static Intent buildAccessibilitySettingsIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    /**
     * Android 13+ blocks switching accessibility on for sideloaded apps until the user
     * allows restricted settings (App info -> menu -> "Allow restricted settings").
     * Root can write the setting directly, but the user should still know why the
     * toggle may look greyed out.
     */
    public static boolean mayBeBlockedByRestrictedSettings(Context context) {
        return Build.VERSION.SDK_INT >= 33 && !isOurServiceEnabled(context);
    }

    /** One-line state for the UI. */
    public static String describe(Context context) {
        if (!isOurServiceEnabled(context)) {
            return isAccessibilityEnabled(context)
                    ? "未开启：无障碍总开关是开的，但 Tally 的「记账屏幕同步助手」不在启用列表里。"
                    : "未开启：Tally 的无障碍服务当前是关闭的。";
        }
        return "已开启：「记账屏幕同步助手」已在系统无障碍中启用并连接。";
    }
}
