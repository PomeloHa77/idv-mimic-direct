package com.fj.direct;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 音量键开关悬浮窗：**音量减 = 隐藏，音量加 = 显示**。
 *
 * 怎么在「不抢焦点、不改游戏代码」的前提下拿到音量键：
 *   按键在 app 内部先交给 Window.Callback（正常情况下就是 Activity 自己）的
 *   dispatchKeyEvent，所以我们用动态代理把 Activity 的 window callback 包一层，
 *   只对音量键插手，其余调用**原样转发**给原来的 callback —— 游戏自己的按键行为
 *   一个字节都不变。
 *
 * 为什么不去把悬浮窗设成可获焦（FLAG_NOT_FOCUSABLE 去掉）：那样窗口会抢走输入焦点，
 * 手柄、物理键盘、输入法都可能被截到我们这儿来，游戏就没法正常操作了。
 *
 * 只有在**真起作用**时才吃掉按键（dispatchKeyEvent 返回 true）：
 *   悬浮窗本来就已经隐藏、还按音量减 → 不拦，正常调音量；
 *   悬浮窗本来就已经显示、还按音量加 → 不拦，正常调音量。
 * 这样音量控制只在「恰好要切换」的那一次被占用，其余时候还是系统的。
 */
final class KeyToggle {

    private static final String TAG = MemScanner.TAG;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 已经包过的 Window → 原 callback（防止重复包、也方便排查）。 */
    private static final WeakHashMap<Window, Window.Callback> WRAPPED =
            new WeakHashMap<Window, Window.Callback>();
    private static volatile boolean sInstalled;

    /** 补捞间隔：新起来的 Activity 最多 2 s 内会被挂上钩子。 */
    private static final long SWEEP_INTERVAL = 2000L;

    /**
     * 持续补捞。为什么不能只靠注册生命周期回调：这个包用网易 unifix 热更新代理，
     * manifest 里的 UFProxyApplication 只是代理，ActivityThread 真正分发事件用的
     * Application 实例可能不是我们注册的那个 —— 真机实测「注册成功但 onActivityResumed
     * 一次都没回调」。所以每隔 2 s 自己反射扫一遍 ActivityThread.mActivities，
     * 把没挂过钩子的 Activity 挂上（已挂过的靠 WRAPPED 去重，不会重复包）。
     */
    private static final Runnable SWEEP = new Runnable() {
        @Override
        public void run() {
            sweepExisting();
            MAIN.postDelayed(this, SWEEP_INTERVAL);
        }
    };

    /** 上一次的音乐音量，用于「音量变化」兜底通道判断是加还是减。 */
    private static int sLastVolume = -1;
    /** 上一次记录的已包 Window 数量（避免补捞日志刷屏）。 */
    private static int sLastWrapped;

    private KeyToggle() {
    }

    /**
     * 装钩子。两件事都要做，缺一不可：
     *
     * 1. **注册生命周期回调**（拿以后才会创建的 Activity）；
     * 2. **主动反射捞当前已经在跑的 Activity**（`ActivityThread.mActivities`）。
     *
     * 为什么必须补第 2 步：这个包用的是网易 unifix 热更新代理，manifest 里的
     * `UFProxyApplication` 只是代理，真正被 ActivityThread 分发生命周期的 Application
     * 实例可能不是我们注册回调的那个对象 —— 真机实测过：注册成功、日志打了、
     * 但 `onActivityResumed` 一次都没回调（也就拿不到 Activity 去挂按键）。
     * 所以除了注册，还要自己把已经存在的 Activity 捞出来挂一遍，并在启动后几秒内多捞几次。
     */
    static void install(Application app) {
        try {
            if (!sInstalled) {
                sInstalled = true;
                registerOn(app);
                Application real = realApplication();
                if (real != null && real != app) {
                    registerOn(real);
                }
                installVolumeObserver(app);
                Log.i(TAG, "音量键开关已装上：音量减=隐藏悬浮窗，音量加=显示");
            }
            sweepExisting();
            MAIN.removeCallbacks(SWEEP);
            MAIN.postDelayed(SWEEP, SWEEP_INTERVAL);
        } catch (Throwable t) {
            Log.e(TAG, "装音量键开关失败：" + t);
        }
    }

    /**
     * 兜底通道：监听音乐音量的变化。
     *
     * 为什么需要它：部分 ROM（含 MIUI）会在系统层就把音量键处理掉，app 的
     * dispatchKeyEvent 根本收不到；但音量值本身变了是能观察到的
     * （Settings.System 读 + ContentObserver，不需要任何权限）。
     * 音量变小 → 隐藏；音量变大 → 显示。
     *
     * 和按键通道不冲突：按键被我们吃掉时音量不会变，观察者自然不会触发；
     * 反过来观察者触发时按键也没被吃，两条路都只做「状态切换」，不会来回抖。
     */
    private static void installVolumeObserver(final Context ctx) {
        try {
            final ContentResolver cr = ctx.getContentResolver();
            sLastVolume = Settings.System.getInt(cr, "volume_music", -1);
            cr.registerContentObserver(Settings.System.getUriFor("volume_music"), false,
                    new ContentObserver(MAIN) {
                        @Override
                        public void onChange(boolean selfChange) {
                            try {
                                int v = Settings.System.getInt(cr, "volume_music", -1);
                                if (v < 0 || sLastVolume < 0 || v == sLastVolume) {
                                    sLastVolume = v;
                                    return;
                                }
                                boolean down = v < sLastVolume;
                                sLastVolume = v;
                                Log.i(TAG, "音量变化（兜底通道）：" + (down ? "减小" : "增大") + " → " + v);
                                if (down) {
                                    if (OverlayWindow.isVisible()) {
                                        OverlayWindow.hideAll();
                                    }
                                } else if (!OverlayWindow.isVisible()) {
                                    OverlayWindow.showAll();
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "读音量失败：" + t);
                            }
                        }
                    });
            Log.i(TAG, "音量变化兜底通道已装上（起始音量 " + sLastVolume + "）");
        } catch (Throwable t) {
            Log.w(TAG, "注册音量观察者失败：" + t);
        }
    }

    /** 在指定的 Application 上注册生命周期回调。 */
    private static void registerOn(Application app) {
        if (app == null) {
            return;
        }
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    // 不在这里挂：onCreate 里 window 的 callback 还能被 Activity 自己覆盖掉
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    // ignore
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    wrap(activity);
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    // ignore
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    // ignore
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                    // ignore
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    // ignore
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "注册 Activity 生命周期回调失败：" + t);
        }
    }

    /**
     * 反射 ActivityThread.mActivities，把当前已经存在的 Activity 全部挂一遍。
     * 这是「注册回调不生效」时的兜底，也是已经跑起来的 Activity 唯一能补挂的途径。
     */
    private static void sweepExisting() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            if (thread == null) {
                return;
            }
            java.lang.reflect.Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object map = f.get(thread);
            if (!(map instanceof Map)) {
                return;
            }
            int n = 0;
            for (Object rec : ((Map<?, ?>) map).values()) {
                if (rec == null) {
                    continue;
                }
                java.lang.reflect.Field af = rec.getClass().getDeclaredField("activity");
                af.setAccessible(true);
                Object a = af.get(rec);
                if (a instanceof Activity) {
                    wrap((Activity) a);
                    n++;
                }
            }
            int total = WRAPPED.size();
            if (n > 0 && total > sLastWrapped) {
                sLastWrapped = total;
                Log.i(TAG, "补捞到 " + total + " 个 Activity 的按键回调");
            }
        } catch (Throwable t) {
            Log.w(TAG, "捞已有 Activity 失败：" + t);
        }
    }

    /** ActivityThread 里真正在被分发事件的那个 Application（unifix 代理会换实例）。 */
    private static Application realApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            if (thread != null) {
                try {
                    Method m = at.getDeclaredMethod("getApplication");
                    m.setAccessible(true);
                    Object a = m.invoke(thread);
                    if (a instanceof Application) {
                        return (Application) a;
                    }
                } catch (Throwable ignore) {
                    // 换个办法
                }
            }
            Method ca = at.getDeclaredMethod("currentApplication");
            ca.setAccessible(true);
            Object a = ca.invoke(null);
            if (a instanceof Application) {
                return (Application) a;
            }
        } catch (Throwable ignore) {
            // ignore
        }
        return null;
    }

    /** 把 activity 的 window callback 换成我们的代理（每个 Window 只包一次）。 */
    private static void wrap(final Activity activity) {
        try {
            if (activity == null) {
                return;
            }
            final Window w = activity.getWindow();
            if (w == null) {
                return;
            }
            Window.Callback cur = w.getCallback();
            if (cur == null || WRAPPED.containsKey(w)) {
                return;
            }
            WRAPPED.put(w, cur);
            Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(
                    KeyToggle.class.getClassLoader(),
                    new Class<?>[] { Window.Callback.class },
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method m, Object[] args) throws Throwable {
                            if ("dispatchKeyEvent".equals(m.getName())
                                    && args != null && args.length == 1
                                    && args[0] instanceof KeyEvent) {
                                KeyEvent ke = (KeyEvent) args[0];
                                if (ke.getAction() == KeyEvent.ACTION_DOWN) {
                                    // 诊断行：能看出按键到底有没有送到 app（音量键被系统吃掉时这里不会有音量键）
                                    Log.i(TAG, "收到按键 " + KeyEvent.keyCodeToString(ke.getKeyCode())
                                            + " repeat=" + ke.getRepeatCount());
                                }
                                if (handle(ke)) {
                                    return Boolean.TRUE;   // 已处理：不给游戏、也不调音量
                                }
                            }
                            try {
                                return m.invoke(cur, args);
                            } catch (InvocationTargetException e) {
                                // 代理必须把原实现抛出的异常原样抛出去
                                throw e.getCause() != null ? e.getCause() : e;
                            }
                        }
                    });
            w.setCallback(proxy);
            Log.i(TAG, "已挂上按键回调：" + activity.getClass().getName());
        } catch (Throwable t) {
            Log.w(TAG, "挂按键回调失败：" + t);
        }
    }

    /** @return true 表示这次按键被我们吃掉（不要传给游戏、也不要调音量） */
    private static boolean handle(KeyEvent e) {
        int k = e.getKeyCode();
        if (k != KeyEvent.KEYCODE_VOLUME_DOWN && k != KeyEvent.KEYCODE_VOLUME_UP) {
            return false;
        }
        // 只认「第一次按下」，长按连发不重复处理
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) {
            return false;
        }
        if (k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (OverlayWindow.isVisible()) {
                OverlayWindow.hideAll();
                return true;
            }
            return false;   // 本来就隐藏着，音量减继续按原样调音量
        }
        if (!OverlayWindow.isVisible()) {
            OverlayWindow.showAll();
            return true;
        }
        return false;       // 本来就显示着，音量加继续按原样调音量
    }
}
