package com.example.budgetapp.keepalive;

import android.app.Activity;
import android.content.Intent;

import androidx.appcompat.app.AlertDialog;

import com.example.budgetapp.ui.KeepAliveSettingsActivity;

/**
 * The first-launch chooser between normal and root keep-alive.
 *
 * <p>The dialog only records the choice and hands off: the actual root probing and
 * module installation happen on {@link KeepAliveSettingsActivity}, so there is one
 * place that owns {@code su} usage and one place that explains failures.
 */
public final class KeepAliveOnboarding {

    /** Extra for {@link KeepAliveSettingsActivity}: run detection immediately on open. */
    public static final String EXTRA_AUTO_SETUP = "keepalive_auto_setup";

    private KeepAliveOnboarding() {
    }

    /**
     * Shows the chooser the first time the app is opened.
     *
     * @param force re-show even if the user has already been asked (Settings entry point)
     */
    public static void showIfNeeded(Activity activity, boolean force) {
        showIfNeeded(activity, force, null);
    }

    /**
     * @param after runs once this dialog is gone, whichever button was used. Lets the
     *              caller queue the next first-launch dialog instead of stacking them.
     */
    public static void showIfNeeded(Activity activity, boolean force, Runnable after) {
        if (!force && KeepAliveMode.hasBeenPrompted(activity)) {
            if (after != null) after.run();
            return;
        }
        KeepAliveMode.markPrompted(activity);
        show(activity, after);
    }

    public static void show(Activity activity) {
        show(activity, null);
    }

    public static void show(Activity activity, Runnable after) {
        AlertDialog alert = new AlertDialog.Builder(activity)
                .setTitle("选择保活方式")
                .setMessage("Tally 通过无障碍服务自动记账。\n"
                        + "有些系统（小米 / 红米等）会在后台自动关闭无障碍，导致漏记账。\n\n"
                        + "现在选一种保活方式，之后随时可以在「设置 → 保活设置」里更改。")
                .setPositiveButton("Root 保活模式（推荐）", (dialog, which) -> {
                    KeepAliveMode.set(activity, KeepAliveMode.MODE_ROOT);
                    openSettings(activity, true);
                })
                .setNeutralButton("普通模式", (dialog, which) -> {
                    KeepAliveMode.set(activity, KeepAliveMode.MODE_NORMAL);
                    requestBatteryExemption(activity);
                })
                .setNegativeButton("稍后再说", null)
                .create();
        if (after != null) {
            alert.setOnDismissListener(d -> after.run());
        }
        alert.show();
    }

    private static void openSettings(Activity activity, boolean autoSetup) {
        Intent intent = new Intent(activity, KeepAliveSettingsActivity.class);
        intent.putExtra(EXTRA_AUTO_SETUP, autoSetup);
        activity.startActivity(intent);
    }

    /**
     * Normal mode's only real lever: the Doze exemption. Failures are ignored here - the
     * settings page surfaces them with instructions.
     */
    private static void requestBatteryExemption(Activity activity) {
        try {
            activity.startActivity(BatteryOptimizationHelper.buildRequestIntent(activity));
        } catch (Exception ignored) {
            openSettings(activity, false);
        }
    }
}
