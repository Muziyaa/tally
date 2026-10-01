package com.example.budgetapp.keepalive;

import android.app.Activity;
import android.content.Context;

import androidx.appcompat.app.AlertDialog;

/**
 * One-time explanation of what this build turns on by default.
 *
 * <p>The app now ships with the permissions and switches already set the way most people
 * want them: silent recording, a small confirmation card, and the three system permissions
 * the guard needs. Nothing is hidden, but defaults that act on the user's behalf should be
 * stated plainly once - otherwise the first symptom is a silent surprise ("why did this
 * thing just record my payment?" or "why is there a card on my screen?").
 *
 * <p>Shown once. The flag is separate from the keep-alive chooser's, because the two
 * dialogs answer different questions and a user may have answered one long before this
 * build arrived.
 */
public final class DefaultsNotice {

    private static final String PREFS = "app_prefs";
    private static final String KEY_SHOWN = "defaults_notice_shown";

    private DefaultsNotice() {
    }

    public static boolean hasBeenShown(Context context) {
        return prefs(context).getBoolean(KEY_SHOWN, false);
    }

    /** Shows the notice unless it has already been shown. */
    public static void showIfNeeded(Activity activity) {
        showIfNeeded(activity, null);
    }

    /**
     * @param after runs once the notice is gone. The first-launch dialogs are chained this
     *              way (notice first, keep-alive chooser last) so they never stack - and the
     *              chooser has to be last, because picking "Root" navigates away and would
     *              cut off anything queued behind it.
     */
    public static void showIfNeeded(Activity activity, Runnable after) {
        if (hasBeenShown(activity)) {
            if (after != null) after.run();
            return;
        }
        show(activity, after);
    }

    public static void show(Activity activity) {
        show(activity, null);
    }

    public static void show(Activity activity, Runnable after) {
        prefs(activity).edit().putBoolean(KEY_SHOWN, true).apply();

        AlertDialog alert = new AlertDialog.Builder(activity)
                .setTitle("已为你默认开启")
                .setMessage("为了装好就能用，下面这些默认都是开着的，随时可以在"
                        + "「设置 → 保活设置」里改：\n\n"
                        + "· 自动记账 —— 识别到付款/收款后自动记一笔\n"
                        + "· 静默记账 —— 直接入账，不弹确认框\n"
                        + "· 记账提示卡片 —— 屏幕顶部显示金额和时间，可撤销、可点进来看\n"
                        + "· 显示悬浮窗权限 —— 上面这张卡片需要它才能显示\n"
                        + "· 后台弹出界面权限 —— 防止系统拦截后台记账\n"
                        + "· 电池白名单 —— 防止系统休眠时冻结记账服务\n\n"
                        + "记账结果都会写进「明细」，不会丢。如果不想被卡片打扰，"
                        + "在保活设置里关掉「显示后台记账提示卡片」即可。")
                .setPositiveButton("知道了", null)
                .create();
        if (after != null) {
            alert.setOnDismissListener(d -> after.run());
        }
        alert.show();
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
