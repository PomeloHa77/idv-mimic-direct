package com.fj.direct;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 注入入口。
 *
 * 由 smali 补丁在 com.netease.ntunisdk.unifix_hotfix_library.proxyApplication.UFProxyApplication
 * 的 attachBaseContext / onCreate 中调用：
 *   attachBaseContext → Boot.boot(Landroid/content/Context;)
 *   onCreate          → Boot.ensure()
 *
 * 逻辑必须极轻：这里只保存 Context 并 post 到主线程去建悬浮窗，不做同步耗时操作。
 */
public final class Boot {

    private static final String TAG = "FJDirect";

    private static volatile Context sCtx;
    private static volatile boolean sBooted;

    private Boot() {
    }

    /** 来自 attachBaseContext（有 Context）。 */
    public static void boot(Context ctx) {
        try {
            if (ctx != null) {
                Context app = null;
                try {
                    app = ctx.getApplicationContext();
                } catch (Throwable ignore) {
                    // ignore
                }
                sCtx = app != null ? app : ctx;
            }
            if (sBooted) {
                return;
            }
            sBooted = true;
            Log.i(TAG, "Boot.boot 注入成功，context=" + (sCtx != null));
            OverlayWindow.scheduleInstall(sCtx);
        } catch (Throwable t) {
            Log.e(TAG, "Boot.boot 失败", t);
        }
    }

    /** 来自 onCreate（无参，兜底重试）。 */
    public static void ensure() {
        try {
            if (sCtx == null) {
                sCtx = resolveApplication();
            }
            Log.i(TAG, "Boot.ensure context=" + (sCtx != null));
            if (sCtx != null) {
                OverlayWindow.scheduleInstall(sCtx);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Boot.ensure 失败", t);
        }
    }

    /** 反射兜底：ActivityThread.currentApplication()。 */
    private static Context resolveApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable t) {
            Log.w(TAG, "反射获取 Application 失败：" + t);
        }
        return null;
    }
}
