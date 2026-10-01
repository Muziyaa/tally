package com.example.budgetapp.keepalive;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The user's choices for which system permissions the root guard should enforce.
 *
 * <p>Three permissions matter for silent, reliable auto-recording:
 * <ul>
 *   <li><b>显示悬浮窗</b> ({@code SYSTEM_ALERT_WINDOW}) - with it, every capture pops a
 *       confirmation window; without it, captures are written straight to the database
 *       with no UI at all. Some users prefer the silent form.</li>
 *   <li><b>后台弹出界面</b> - a HyperOS/MIUI vendor appop. Without it the app may be
 *       unable to show anything while in the background, which is how a confirmation
 *       window ends up "shown" but invisible.</li>
 *   <li><b>电池优化白名单</b> - the Doze whitelist, so the system is less eager to stop
 *       the app in the background.</li>
 * </ul>
 *
 * <p>Each is {@link #ALLOW}, {@link #DENY} or {@link #IGNORE}. {@code IGNORE} means the
 * guard leaves it exactly as the user set it by hand. {@code ALLOW} is the default, so the
 * app works out of the box; previously the default was {@code IGNORE}, which left a fresh
 * install unable to record in the background until the user found this page. The old note
 * that used to sit here read: installing the
 * guard never silently changes a permission the user did not ask about.
 *
 * <p>These values live in {@code app_prefs}, which the guard reads directly as root, so
 * there is no second protocol to keep in sync.
 */
public final class PermissionAutomation {

    public static final String ALLOW = "allow";
    public static final String DENY = "deny";
    public static final String IGNORE = "ignore";

    private static final String PREFS = "app_prefs";
    private static final String KEY_OVERLAY = "auto_perm_overlay";
    private static final String KEY_BG_POPUP = "auto_perm_bgpopup";
    private static final String KEY_BATTERY = "auto_perm_battery";
    /** POST_NOTIFICATIONS —— 记账提示用系统通知时必须授予。 */
    private static final String KEY_NOTIFICATION = "auto_perm_notification";
    private static final String KEY_OVERLAY_NAG_DISABLED = "nag_overlay_disabled";

    private PermissionAutomation() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String read(Context context, String key) {
        // 默认「允许」：装好就该能用，不需要用户再去翻三层系统设置。想改的人可以在
        // 「设置 → 保活设置 → 自动化权限」里逐项改掉。
        String value = prefs(context).getString(key, ALLOW);
        if (ALLOW.equals(value) || DENY.equals(value)) {
            return value;
        }
        return IGNORE;
    }

    private static void write(Context context, String key, String value) {
        String normalised = (ALLOW.equals(value) || DENY.equals(value)) ? value : IGNORE;
        prefs(context).edit().putString(key, normalised).apply();
    }

    public static String getOverlay(Context context) {
        return read(context, KEY_OVERLAY);
    }

    public static void setOverlay(Context context, String value) {
        write(context, KEY_OVERLAY, value);
    }

    public static String getBgPopup(Context context) {
        return read(context, KEY_BG_POPUP);
    }

    public static void setBgPopup(Context context, String value) {
        write(context, KEY_BG_POPUP, value);
    }

    public static String getNotification(Context context) {
        return read(context, KEY_NOTIFICATION);
    }

    public static void setNotification(Context context, String value) {
        write(context, KEY_NOTIFICATION, value);
    }

    public static String getBattery(Context context) {
        return read(context, KEY_BATTERY);
    }

    public static void setBattery(Context context, String value) {
        write(context, KEY_BATTERY, value);
    }

    /** Manual "don't remind me again" for the overlay-permission dialog. */
    public static boolean isOverlayNagDisabled(Context context) {
        return prefs(context).getBoolean(KEY_OVERLAY_NAG_DISABLED, false);
    }

    public static void setOverlayNagDisabled(Context context, boolean disabled) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_NAG_DISABLED, disabled).apply();
    }

    /**
     * Whether the app should stop nagging about the overlay permission.
     *
     * <p>True when the user deliberately chose silent recording (so the permission is
     * unwanted, not missing), or explicitly asked not to be reminded again.
     */
    public static boolean shouldSuppressOverlayNag(Context context) {
        return DENY.equals(getOverlay(context)) || isOverlayNagDisabled(context);
    }

    /** True when the user picked silent recording, so recording needs no window at all. */
    public static boolean isSilentRecording(Context context) {
        return DENY.equals(getOverlay(context));
    }

    public static String label(String value) {
        if (ALLOW.equals(value)) {
            return "自动开启";
        }
        if (DENY.equals(value)) {
            return "自动关闭";
        }
        return "不干预";
    }

    /** Index into {@link #CHOICES}, for the settings spinners. */
    public static final String[] CHOICES = {ALLOW, DENY, IGNORE};

    public static int indexOf(String value) {
        for (int i = 0; i < CHOICES.length; i++) {
            if (CHOICES[i].equals(value)) {
                return i;
            }
        }
        return 2;
    }
}
