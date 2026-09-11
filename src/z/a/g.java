package z.a;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 音量键手势（原 com.fj.direct.KeyToggle）：悬浮窗不可触摸后，全部交互改由这里驱动。
 *
 * 手势表（面板显示中）：
 *   音量加 短按            = 扫描
 *   音量加 按住 3.0 秒     = 复制结果到剪贴板
 *   音量减 短按            = 收起面板（收起后音量键就还给系统了）
 *   音量减 按住 5.0 秒     = 解锁触摸 10 秒（可拖动面板、点按钮；再按住 5 秒立即上锁）
 * 手势表（面板收起后）：
 *   音量减                 = 原样交给系统，正常调音量
 *   音量加                 = 叫回面板，同时由我们自己把这一下音量补上
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
 * 为什么「面板显示中」必须把音量键吃掉：要区分「短按」和「按住 3/5 秒」，
 * 就必须先把 DOWN 拦下来、自己计时；否则系统会先把音量调掉、界面还弹出来。
 *
 * 为什么「面板收起后」要放行音量减：以前不看状态一律吃掉，用户在对局里想调音量
 * 发现按不动 —— 这就是「抢按键」的真正来源。现在只有需要区分长短按的那个状态
 * （面板显示中）才拦，收起后音量键就是系统的。
 *
 * 为什么收起后音量加仍然拦、还自己补一次音量：必须留一条「把面板叫回来」的路；
 * 而系统音量已经最大时按键不产生音量变化，靠「音量变化」那条兜底路会失灵。
 * 所以这里拦下音量加、用 AudioManager 自己调一次（带系统音量条），观感与原生一致。
 *
 * 为什么补捞不放在主线程：SWEEP 要反射 ActivityThread.mActivities（隐藏 API）。
 * 真机实测放在主线程时，每 2 s 一次会在游戏里稳定掉一帧。现在整个反射过程跑在
 * 自己的后台 HandlerThread 上，只有「发现没挂过钩子的 Activity」才 post 一次到主线程。
 */
final class g {


    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 已经包过的 Window → 原 callback（防止重复包、也方便排查）。
     *
     * 必须同步：补捞在后台线程读它、挂钩子在主线程写它。
     */
    private static final java.util.Map<Window, Window.Callback> WRAPPED =
            java.util.Collections.synchronizedMap(
                    new WeakHashMap<Window, Window.Callback>());
    private static volatile boolean sInstalled;

    /** 应用 Context（收起面板时补音量要用）。 */
    private static volatile Context sApp;

    /**
     * 按键通道是否真的收到过音量键。
     *
     * 为什么要这个标记：见 installVolumeObserver 的注释 —— 按键通道一旦生效，
     * 「音量变化」那条兜底通道就必须闭嘴，否则两条通道会互相打架。
     */
    private static volatile boolean sKeySeen;

    /** 补捞间隔：刚发现新窗口用快的，长时间没动静才降频。 */
    private static final long SWEEP_FAST = 3000L;
    private static final long SWEEP_SLOW = 12000L;
    /** 连续这么多轮没发现新 Activity 就降频。 */
    private static final int SWEEP_IDLE_ROUNDS = 4;
    /** 当前间隔 / 连续空闲轮数（只在补捞线程上读写）。 */
    private static long sSweepInterval = SWEEP_FAST;
    private static int sIdleRounds;
    /** 补捞线程上的 Handler：整个反射过程都在它上面跑，绝不上主线程。 */
    private static volatile Handler sSweepHandler;

    /**
     * 持续补捞。为什么不能只靠注册生命周期回调：这个包用网易 unifix 热更新代理，
     * manifest 里的 UFProxyApplication 只是代理，ActivityThread 真正分发事件用的
     * Application 实例可能不是我们注册的那个 —— 真机实测「注册成功但 onActivityResumed
     * 一次都没回调」。所以定期自己反射扫一遍 ActivityThread.mActivities，
     * 把没挂过钩子的 Activity 挂上（已挂过的靠 WRAPPED 去重，不会重复包）。
     *
     * 反射 + 遍历全程在后台线程；只有发现新 Activity 才 post 一次到主线程。
     */
    private static final Runnable SWEEP = new Runnable() {
        @Override
        public void run() {
            int found = collectAndWrap();
            if (found > 0) {
                sIdleRounds = 0;
                sSweepInterval = SWEEP_FAST;
            } else {
                sIdleRounds++;
                if (sIdleRounds >= SWEEP_IDLE_ROUNDS) {
                    sSweepInterval = SWEEP_SLOW;
                }
            }
            Handler h = sSweepHandler;
            if (h != null) {
                h.postDelayed(this, sSweepInterval);
            }
        }
    };

    /** 上一次的音乐音量，用于「音量变化」兜底通道判断是加还是减。 */
    private static int sLastVolume = -1;
    /** 上一次记录的已包 Window 数量（避免补捞日志刷屏）。 */
    private static int sLastWrapped;

    /** 音量加长按 = 复制结果，判定阈值。 */
    private static final long LONG_COPY_MS = 3000L;
    /** 音量减长按 = 解锁/上锁触摸，判定阈值。 */
    private static final long LONG_UNLOCK_MS = 5000L;

    /** 音量加当前是否按住 / 是否已经触发过长按动作。 */
    private static boolean sUpDown;
    private static boolean sUpLong;
    /** 音量减当前是否按住 / 是否已经触发过长按动作。 */
    private static boolean sDownDown;
    private static boolean sDownLong;

    private g() {
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
            sApp = app;
            if (!sInstalled) {
                sInstalled = true;
                registerOn(app);
                Application real = realApplication();
                if (real != null && real != app) {
                    registerOn(real);
                }
                installVolumeObserver(app);
                i.i("手势钩子已装上：音量加=扫描/长按复制，音量减=显示隐藏/长按解锁");
            }
            startSweeper();
        } catch (Throwable t) {
            i.e("装音量键开关失败：" + t);
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
     * 和按键通道不冲突：一旦按键通道真的收到过音量键（sKeySeen 置位），这条通道
     * 就永久失效（直接 return）。否则「收起面板后音量键放行」的新行为会立刻和它
     * 打架 —— 用系统音量条调一下，我们的面板就被它藏起来 / 叫出来了。
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
                                if (sKeySeen) {
                                    // 按键通道已经能收到音量键，兜底通道就该闭嘴
                                    return;
                                }
                                i.i("音量变化（兜底通道）：" + (down ? "减小" : "增大") + " → " + v);
                                if (down) {
                                    if (f.isVisible()) {
                                        f.hideAll();
                                    }
                                } else if (!f.isVisible()) {
                                    f.showAll();
                                }
                            } catch (Throwable t) {
                                i.w("读音量失败：" + t);
                            }
                        }
                    });
            i.i("音量变化兜底通道已装上（起始音量 " + sLastVolume + "）");
        } catch (Throwable t) {
            i.w("注册音量观察者失败：" + t);
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
            i.w("注册 Activity 生命周期回调失败：" + t);
        }
    }

    /** 启动补捞线程（只启一次）。 */
    private static void startSweeper() {
        if (sSweepHandler != null) {
            return;
        }
        synchronized (g.class) {
            if (sSweepHandler != null) {
                return;
            }
            android.os.HandlerThread t = new android.os.HandlerThread("fjsweep",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            Handler h = new Handler(t.getLooper());
            sSweepHandler = h;
            h.postDelayed(SWEEP, 500L);
        }
    }

    /** 这个 Activity 的 window 是否已经挂过钩子（后台线程调用，只读 map）。 */
    private static boolean isWrapped(Activity a) {
        try {
            Window w = a.getWindow();
            return w == null || WRAPPED.containsKey(w);
        } catch (Throwable t) {
            return true;    // 查不出来就当已挂过，别在主线程做多余动作
        }
    }

    /**
     * 在**后台线程**反射 ActivityThread.mActivities，找出还没挂钩子的 Activity，
     * 再 post 到主线程去 wrap。整个反射 + 遍历都不在主线程上，避免周期性掉帧
     * （真机实测：这活儿放主线程时，游戏里每 2 s 稳定掉一帧）。
     *
     * @return 这一轮发现的新 Activity 个数（0 = 没有新东西，用来决定降频）
     */
    private static int collectAndWrap() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method cur = at.getDeclaredMethod("currentActivityThread");
            cur.setAccessible(true);
            Object thread = cur.invoke(null);
            if (thread == null) {
                return 0;
            }
            java.lang.reflect.Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object map = f.get(thread);
            if (!(map instanceof Map)) {
                return 0;
            }
            final java.util.ArrayList<Activity> todo = new java.util.ArrayList<Activity>(4);
            for (Object rec : ((Map<?, ?>) map).values()) {
                if (rec == null) {
                    continue;
                }
                java.lang.reflect.Field af = rec.getClass().getDeclaredField("activity");
                af.setAccessible(true);
                Object a = af.get(rec);
                if (a instanceof Activity) {
                    Activity act = (Activity) a;
                    if (!isWrapped(act)) {
                        todo.add(act);
                    }
                }
            }
            if (todo.isEmpty()) {
                return 0;
            }
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    for (int k = 0; k < todo.size(); k++) {
                        wrap(todo.get(k));
                    }
                    int total = WRAPPED.size();
                    if (total > sLastWrapped) {
                        sLastWrapped = total;
                        i.i("补捞到 " + total + " 个 Activity 的按键回调");
                    }
                }
            });
            return todo.size();
        } catch (Throwable t) {
            i.w("捞已有 Activity 失败：" + t);
            return 0;
        }
    }

    /**
     * 面板收起时按音量加：把这一下音量自己补上。
     *
     * 为什么：这一下被我们吃掉用来叫回面板了，如果不补，用户会觉得「音量键失灵」。
     * adjustStreamVolume 是公开 API，效果与系统自己调一致（带系统音量条），需要
     * MODIFY_AUDIO_SETTINGS —— 原包清单里本来就有，不用改清单。
     */
    private static void adjustVolume() {
        try {
            Context ctx = sApp;
            if (ctx == null) {
                return;
            }
            android.media.AudioManager am =
                    (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE,
                    android.media.AudioManager.FLAG_SHOW_UI);
        } catch (Throwable t) {
            i.w("补音量失败：" + t);
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
                    g.class.getClassLoader(),
                    new Class<?>[] { Window.Callback.class },
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method m, Object[] args) throws Throwable {
                            if ("dispatchKeyEvent".equals(m.getName())
                                    && args != null && args.length == 1
                                    && args[0] instanceof KeyEvent) {
                                KeyEvent ke = (KeyEvent) args[0];
                                if (ke.getAction() == KeyEvent.ACTION_DOWN
                                        && ke.getRepeatCount() == 0
                                        && (ke.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                                            || ke.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                                    // 诊断行：能看出音量键到底有没有送到 app（被系统吃掉时这里不会有）
                                    i.i("收到音量键 " + KeyEvent.keyCodeToString(ke.getKeyCode()));
                                }
                                if (handle(ke)) {
                                    return Boolean.TRUE;   // 已处理：不给游戏、也不调音量
                                }
                            }
                            // 只在 Debug 构建里打：游戏侧「自己的」触摸事件有没有被系统打上
                            // FLAG_WINDOW_IS_OBSCURED（bit0）。这是悬浮窗那条暴露面的现场取证：
                            // 窗口设成 NOT_TOUCHABLE 后，这里必须永远是「被遮挡=false」。
                            // release 构建里 i.ON 是编译期常量 false，整段被 javac 消掉。
                            if (i.ON && "dispatchTouchEvent".equals(m.getName())
                                    && args != null && args.length == 1
                                    && args[0] instanceof MotionEvent) {
                                MotionEvent me = (MotionEvent) args[0];
                                if (me.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                    int f = me.getFlags();
                                    i.i("触摸 flags=0x" + Integer.toHexString(f)
                                            + " 被遮挡=" + ((f & 0x1) != 0)
                                            + " @" + (int) me.getRawX() + "," + (int) me.getRawY());
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
            i.i("已挂上按键回调：" + activity.getClass().getName());
        } catch (Throwable t) {
            i.w("挂按键回调失败：" + t);
        }
    }

    /** 音量加按住 3 秒：复制结果。 */
    private static final Runnable LONG_UP = new Runnable() {
        @Override
        public void run() {
            if (!sUpDown) {
                return;
            }
            sUpLong = true;
            i.i("手势：音量加长按 → 复制");
            f.copyText();
        }
    };

    /** 音量减按住 5 秒：解锁触摸；已经解锁中的话立刻上锁。 */
    private static final Runnable LONG_DOWN = new Runnable() {
        @Override
        public void run() {
            if (!sDownDown) {
                return;
            }
            sDownLong = true;
            if (f.touchable()) {
                i.i("手势：音量减长按 → 立即上锁");
                f.lockTouch();
            } else {
                i.i("手势：音量减长按 → 解锁触摸");
                f.unlockTouch();
            }
        }
    };

    /**
     * 音量键状态机。
     *
     * @return true 表示这次按键被我们吃掉（不要传给游戏、也不要调音量）
     */
    private static boolean handle(KeyEvent e) {
        int k = e.getKeyCode();
        if (k != KeyEvent.KEYCODE_VOLUME_DOWN && k != KeyEvent.KEYCODE_VOLUME_UP) {
            return false;
        }
        boolean up = k == KeyEvent.KEYCODE_VOLUME_UP;
        // 按键通道真的收到了音量键 → 关掉「音量变化」兜底通道（两条通道会互相打架）
        sKeySeen = true;
        if (!f.isVisible()) {
            // 面板收起 = 音量键还给系统。只有「音量加」留着当叫回面板的手势，
            // 并且我们自己把这一下音量补上，用户不会觉得按键被吞了。
            if (!up) {
                return false;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                adjustVolume();
                if (e.getRepeatCount() == 0) {
                    i.i("手势：音量加 → 叫回面板");
                    f.showAll();
                }
            }
            return true;
        }
        int action = e.getAction();

        if (action == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) {
                return true;    // 长按连发：吃掉，别让系统顺势连续调音量
            }
            if (up) {
                sUpDown = true;
                sUpLong = false;
                MAIN.postDelayed(LONG_UP, LONG_COPY_MS);
            } else {
                sDownDown = true;
                sDownLong = false;
                MAIN.postDelayed(LONG_DOWN, LONG_UNLOCK_MS);
            }
            return true;
        }

        if (action == KeyEvent.ACTION_UP) {
            MAIN.removeCallbacks(up ? LONG_UP : LONG_DOWN);
            boolean was;
            boolean wasLong;
            if (up) {
                was = sUpDown;
                wasLong = sUpLong;
                sUpDown = false;
                sUpLong = false;
            } else {
                was = sDownDown;
                wasLong = sDownLong;
                sDownDown = false;
                sDownLong = false;
            }
            if (was && !wasLong) {
                if (up) {
                    i.i("手势：音量加短按 → 扫描");
                    f.startScan();
                } else {
                    i.i("手势：音量减短按 → 显示/隐藏");
                    f.toggle();
                }
            }
            return true;
        }

        // ACTION_MULTIPLE 之类：音量键一律吃掉，避免漏给系统
        return true;
    }
}
