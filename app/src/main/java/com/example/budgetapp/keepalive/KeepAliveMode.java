package com.example.budgetapp.keepalive;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists which keep-alive mode the user picked and whether they have been asked yet.
 *
 * <p>Stored in the existing {@code app_prefs} file so it travels with the app's other
 * settings (and with backup/restore) rather than inventing another preferences file.
 */
public final class KeepAliveMode {

    /** Rely on accessibility + battery whitelisting only. */
    public static final String MODE_NORMAL = "normal";

    /** Install the root guard so the accessibility binding is restored automatically. */
    public static final String MODE_ROOT = "root";

    private static final String PREFS = "app_prefs";
    private static final String KEY_MODE = "keepalive_mode";
    private static final String KEY_PROMPTED = "keepalive_mode_prompted";

    private KeepAliveMode() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(Context context) {
        return prefs(context).getString(KEY_MODE, MODE_NORMAL);
    }

    public static void set(Context context, String mode) {
        prefs(context).edit().putString(KEY_MODE, mode).apply();
    }

    public static boolean isRootMode(Context context) {
        return MODE_ROOT.equals(get(context));
    }

    /** False until the first-launch chooser has been shown and answered. */
    public static boolean hasBeenPrompted(Context context) {
        return prefs(context).getBoolean(KEY_PROMPTED, false);
    }

    public static void markPrompted(Context context) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply();
    }
}
