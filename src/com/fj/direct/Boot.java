package com.fj.direct;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
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
    /** 已经判定过「不是主进程」，后续调用直接返回（避免 onCreate 再走一遍）。 */
    private static volatile boolean sSkipped;
    /** 主进程判定结果缓存：null=未判定。 */
    private static volatile Boolean sMain;

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
            // 关键：只在游戏主进程里装悬浮窗。见 isMainProcess() 的注释。
            if (sCtx != null && !isMainProcess(sCtx)) {
                sSkipped = true;
                return;
            }
            sBooted = true;
            Log.i(TAG, "Boot.boot 注入成功，context=" + (sCtx != null));
            installKeyToggle(sCtx);
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
            if (sSkipped) {
                return;
            }
            if (sCtx != null && !isMainProcess(sCtx)) {
                sSkipped = true;
                return;
            }
            Log.i(TAG, "Boot.ensure context=" + (sCtx != null));
            if (sCtx != null) {
                installKeyToggle(sCtx);
                OverlayWindow.scheduleInstall(sCtx);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Boot.ensure 失败", t);
        }
    }

    /** 音量键开关要挂 Activity 生命周期回调，所以必须拿到 Application。 */
    private static void installKeyToggle(Context ctx) {
        try {
            if (ctx instanceof Application) {
                KeyToggle.install((Application) ctx);
                return;
            }
            Context app = resolveApplication();
            if (app instanceof Application) {
                KeyToggle.install((Application) app);
            } else {
                Log.w(TAG, "拿不到 Application：音量键开关未装（悬浮窗的按钮不受影响）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "装音量键开关失败：" + t);
        }
    }

    /**
     * 当前进程是不是游戏主进程。
     *
     * 为什么必须拦：注入点 UFProxyApplication 是这个 app 的 Application 类，
     * 而 Application 在 app 的**每个进程**里都会被创建 —— 这里至少还有
     * com.netease.dwrg.fj:PushService（网易推送进程）。如果每个进程都建悬浮窗，
     * 手机上会出现两个位置完全重叠、长得一模一样的小窗，点到的很可能是推送进程那个；
     * 而扫描器读的是 /proc/self/maps（即「自己这个进程」），在推送进程里读到的
     * 是推送进程的地址空间，角色数据一个都不会有 —— 表现为永远「未命中任何角色」。
     * 扮演者的角色数据只存在游戏主进程里，所以：不是主进程就直接不注入。
     *
     * 判定用 /proc/self/cmdline（全版本可用、零反射风险），拿不到再退回
     * ActivityThread.currentProcessName()。两者都失败时按「是主进程」处理（不误伤）。
     */
    private static boolean isMainProcess(Context ctx) {
        Boolean cached = sMain;
        if (cached != null) {
            return cached;
        }
        boolean main = true;
        try {
            String me = processName();
            String pkg = ctx != null ? ctx.getPackageName() : null;
            if (me != null && pkg != null) {
                main = me.equals(pkg);
            }
            Log.i(TAG, "进程判定：cmdline=" + me + " 包名=" + pkg + " 主进程=" + main);
        } catch (Throwable t) {
            Log.w(TAG, "进程名判定失败，按主进程处理：" + t);
        }
        sMain = main;
        return main;
    }

    /** 读 /proc/self/cmdline；失败退回反射 ActivityThread.currentProcessName()。 */
    private static String processName() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/self/cmdline"));
            String s = br.readLine();
            if (s != null) {
                int z = s.indexOf('\0');
                if (z >= 0) {
                    s = s.substring(0, z);
                }
                s = s.trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        } catch (Throwable ignore) {
            // 退回反射
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignore) {
                    // ignore
                }
            }
        }
        try {
            Method m = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentProcessName");
            m.setAccessible(true);
            Object o = m.invoke(null);
            if (o instanceof String) {
                return (String) o;
            }
        } catch (Throwable ignore) {
            // ignore
        }
        return null;
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
