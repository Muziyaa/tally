package com.example.budgetapp.keepalive;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * The normal (non-root) half of keep-alive.
 *
 * <p>Without root the best available lever is the official Doze exemption: asking the
 * user to whitelist the app is what actually stops the system from freezing it during
 * long idle periods. Unlike hacking process priority it costs no battery - it only
 * permits the app to run, it does not make it run.
 *
 * <p>The OEM autostart lists (MIUI/HyperOS "自启动", "省电策略") have no public API, so
 * those remain a guided manual step.
 */
public final class BatteryOptimizationHelper {

    private BatteryOptimizationHelper() {
    }

    public static boolean isExempt(Context context) {
        PowerManager powerManager =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * The system dialog that asks for the Doze exemption. Requires
     * {@code REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} in the manifest.
     */
    @SuppressLint("BatteryLife")
    public static Intent buildRequestIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    /** Fallback when the OEM intercepts the request dialog. */
    public static Intent buildAppDetailsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }
}
