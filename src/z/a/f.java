package z.a;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.app.AppOpsManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * 悬浮窗（原 com.fj.direct.OverlayWindow）：结果按阵营上色常显，扫描由音量键触发。
 *
 * 关键设计：窗口**全程 FLAG_NOT_TOUCHABLE**（看得见、点不到）。
 * 为什么必须这样：只要窗口可触摸，落在它矩形范围内的每一次点击，输入系统都会给
 * 下层游戏的事件打上 FLAG_WINDOW_IS_OBSCURED（Android 12+ 若游戏窗口是
 * BLOCK_UNTRUSTED，这种触摸甚至会被直接丢弃），这是典型的「有东西盖在上面」信号。
 * 设为不可触摸后，窗口不参与命中测试，游戏侧永远看不到这个标记。
 *
 * 代价是不可触摸的窗口既点不到按钮也拖不动，所以交互全部改由音量键驱动（见 g）：
 *   音量加 短按 = 扫描          音量加 按住 3.0s = 复制结果
 *   音量减 短按 = 显示/隐藏     音量减 按住 5.0s = 解锁触摸 20s（可拖动/点按钮）
 * 解锁窗口倒计时结束自动上锁，保证「对局中」这一常态下窗口始终不可触摸。
 *
 * 清单里自带 android.permission.SYSTEM_ALERT_WINDOW，所以只需要用户在系统设置里
 * 授权一次「显示在其他应用上层」；未授权时退化为 Toast。
 */
public final class f {

    private static final int CAMP_COLOR_DETECTIVE = 0xFF4FA8FF; // 侦探团
    private static final int CAMP_COLOR_WOLF = 0xFFFF5A5A;      // 狼人
    private static final int CAMP_COLOR_MYSTERY = 0xFFFFC93C;   // 神秘客

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context sCtx;
    private static WindowManager sWm;
    private static LinearLayout sRoot;
    private static LinearLayout sPanel;
    private static TextView sChip;
    private static ScrollView sScroll;
    /** 隐藏时用来撑住窗口的 1×1 透明占位视图（见 hideAll 的注释）。 */
    private static View sPlaceholder;
    private static TextView sBody;
    private static Button sScanBtn;
    private static TextView sTitle;
    private static WindowManager.LayoutParams sParams;
    private static volatile boolean sScanning;
    /** 已被音量减键摘下来（窗口不在 WindowManager 里）。 */
    private static volatile boolean sHidden;
    /** 面板被「✕」收成了小方块。 */
    private static volatile boolean sMinimized;
    /** 当前窗口是否可触摸（默认 false = 不可触摸，只有解锁窗口内为 true）。 */
    private static volatile boolean sTouchable;
    /** 解锁窗口剩余秒数。 */
    private static volatile int sUnlockLeft;
    private static int sAttempts;
    /** 「点」与「拖」的分界：位移超过这么多 px 就算拖动，不再当成点击。 */
    private static final int TAP_SLOP = 12;
    /** 解锁后保持可触摸的秒数。 */
    private static final int UNLOCK_SECONDS = 20;
    /** 面板默认标题。 */
    private static final String TITLE = "模仿者·直装";

    private f() {
    }

    public static void scheduleInstall(final Context ctx) {
        if (ctx == null) {
            return;
        }
        sCtx = ctx;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                tryInstall();
            }
        });
    }

    private static void tryInstall() {
        if (sRoot != null) {
            return;
        }
        sAttempts++;
        try {
            Context ctx = sCtx;
            if (ctx == null) {
                i.w("context 为空，无法建悬浮窗");
                return;
            }
            sWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (sWm == null) {
                i.w("WindowManager 为空");
                return;
            }
            sRoot = buildView(ctx);
            sParams = buildParams();
            sWm.addView(sRoot, sParams);
            i.i("悬浮窗已创建（悬浮窗权限=" + (overlayAllowed(ctx) ? "已授权" : "未授权") + "）");
            // 未授权时 addView **不会抛异常**，窗口会进 WindowManager 但被策略隐藏
            // （mPolicyVisibility=false / mAppOpVisibility=false），屏幕上什么都看不到。
            // 所以光靠 try/catch 判断不出「装了但没显示」，必须显式查一下 app-op 并提示。
            if (!overlayAllowed(ctx)) {
                i.w("SYSTEM_ALERT_WINDOW 未授权：窗口已加入但会被系统隐藏");
                toast("悬浮窗被系统拦住：请到「设置 → 应用 → 显示在其他应用上层」允许后重开游戏");
            }
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (sRoot != null && !sHidden && !sRoot.isShown()) {
                            i.w("窗口已加入但未显示（被系统策略隐藏），多半是没授权「显示在其他应用上层」");
                            toast("悬浮窗没显示出来：请授权「显示在其他应用上层」后重开游戏");
                        }
                    } catch (Throwable ignore) {
                        // ignore
                    }
                }
            }, 3000L);
        } catch (Throwable t) {
            sRoot = null;
            i.e("创建悬浮窗失败（可能未授予 SYSTEM_ALERT_WINDOW）：" + t);
            if (sAttempts == 1) {
                toast("请到「设置 → 应用 → 第五人格 → 显示在其他应用上层」授权后重开游戏");
            }
            if (sAttempts <= 10) {
                // 授权后可能不需要重启，定时重试
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        tryInstall();
                    }
                }, 5000L);
            }
        }
    }

    /**
     * 查「显示在其他应用上层」这个 app-op 是否真的放行了。
     *
     * 为什么要专门查：未授权时 WindowManager.addView **不抛异常**，窗口会正常进
     * WindowManager，只是被系统按策略隐藏（mPolicyVisibility=false），
     * 于是「没崩、也没显示」，光靠异常判断不出来。
     * 用字符串 op 名而不是 AppOpsManager.OPSTR_* 常量，是为了不受 API 级别差异影响。
     */
    private static boolean overlayAllowed(Context ctx) {
        try {
            AppOpsManager am = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (am == null) {
                return true;
            }
            int mode = am.checkOpNoThrow("android:system_alert_window",
                    android.os.Process.myUid(), ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return true;    // 查不到就别误报
        }
    }

    private static WindowManager.LayoutParams buildParams() {
        int type;
        if (Build.VERSION.SDK_INT >= 26) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        // 不可触摸是这套方案的核心：窗口不参与触摸命中测试，
                        // 游戏侧的触摸事件就不会再带「被遮挡」标记。
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 24;
        p.y = 160;
        return p;
    }

    private static LinearLayout buildView(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        // 面板本体。点「✕」时整块隐藏，只留 sChip 那个小方块，做到「关掉但随时叫回来」。
        sPanel = new LinearLayout(ctx);
        sPanel.setOrientation(LinearLayout.VERTICAL);
        sPanel.setBackgroundColor(0xCC101010);
        int pad = dp(ctx, 8);
        sPanel.setPadding(pad, pad, pad, pad);
        // 面板宽度必须显式定死：宽度是由下面 ScrollView 的固定宽度决定的，
        // 如果反过来让按钮行的 WRAP_CONTENT 去挤，按钮会被压成 0 宽 ——
        // 真机上就是这么把「收起/✕」裁到窗口外面、点都点不到的。
        sPanel.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 250),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 第一行：标题（占满剩余宽度，同时兼作拖动把手）+「✕」
        final LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText(TITLE);
        title.setTextColor(0xFFDDDDDD);
        title.setTextSize(12f);
        title.setPadding(0, 0, dp(ctx, 4), 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);
        sTitle = title;

        // 「✕」= 最小化成一枚小方块（不是杀进程，也撤不掉系统里的窗口，
        // 这样才不会出现「关掉之后再也叫不回来」的窘境）。
        Button closeBtn = new Button(ctx);
        closeBtn.setText("✕");
        closeBtn.setTextSize(12f);
        closeBtn.setPadding(0, 0, 0, 0);
        closeBtn.setMinimumWidth(dp(ctx, 32));
        closeBtn.setMinimumHeight(dp(ctx, 28));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                minimize();
            }
        });
        bar.addView(closeBtn);
        sPanel.addView(bar);

        // 第二行：三个按钮等分
        LinearLayout bar2 = new LinearLayout(ctx);
        bar2.setOrientation(LinearLayout.HORIZONTAL);
        bar2.setGravity(Gravity.CENTER_VERTICAL);

        sScanBtn = new Button(ctx);
        sScanBtn.setText("扫描");
        sScanBtn.setTextSize(12f);
        sScanBtn.setPadding(0, 0, 0, 0);
        sScanBtn.setMinimumWidth(0);
        sScanBtn.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sScanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        bar2.addView(sScanBtn);

        Button copyBtn = new Button(ctx);
        copyBtn.setText("复制");
        copyBtn.setTextSize(12f);
        copyBtn.setPadding(0, 0, 0, 0);
        copyBtn.setMinimumWidth(0);
        copyBtn.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText();
            }
        });
        bar2.addView(copyBtn);

        Button hideBtn = new Button(ctx);
        hideBtn.setText("收起");
        hideBtn.setTextSize(12f);
        hideBtn.setPadding(0, 0, 0, 0);
        hideBtn.setMinimumWidth(0);
        hideBtn.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        hideBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 要收起的是整个滚动区，不能只藏里面的 TextView ——
                // ScrollView 自己是固定高度的，只藏内容的话窗口大小一点都不会变。
                if (sScroll.getVisibility() == View.VISIBLE) {
                    sScroll.setVisibility(View.GONE);
                    ((Button) v).setText("展开");
                } else {
                    sScroll.setVisibility(View.VISIBLE);
                    ((Button) v).setText("收起");
                }
            }
        });
        bar2.addView(hideBtn);
        sPanel.addView(bar2);

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 240));
        scroll.setLayoutParams(slp);
        sScroll = scroll;

        sBody = new TextView(ctx);
        sBody.setTextColor(0xFFEEEEEE);
        sBody.setTextSize(13f);
        sBody.setText("音量加 短按=扫描 · 按住 3 秒=复制\n"
                + "音量减 短按=显示/隐藏 · 按住 5 秒=解锁触摸 20 秒\n"
                + "窗口默认不可触摸（点不到），解锁后可拖动/点按钮");
        sBody.setPadding(0, dp(ctx, 6), 0, 0);
        scroll.addView(sBody);
        sPanel.addView(scroll);
        root.addView(sPanel);

        // 最小化后剩下的小方块：点一下恢复，按住可以拖动。
        sChip = new TextView(ctx);
        sChip.setText("FJ");
        sChip.setTextSize(12f);
        sChip.setTextColor(0xFF9BD1FF);
        sChip.setBackgroundColor(0xCC101010);
        int cpad = dp(ctx, 8);
        sChip.setPadding(cpad, cpad, cpad, cpad);
        sChip.setVisibility(View.GONE);
        sChip.setOnTouchListener(new View.OnTouchListener() {
            private int downX;
            private int downY;
            private float touchX;
            private float touchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = sParams.x;
                        downY = sParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - touchX);
                        int dy = (int) (event.getRawY() - touchY);
                        if (Math.abs(dx) > TAP_SLOP || Math.abs(dy) > TAP_SLOP) {
                            moved = true;
                        }
                        sParams.x = downX + dx;
                        sParams.y = downY + dy;
                        try {
                            sWm.updateViewLayout(sRoot, sParams);
                        } catch (Throwable ignore) {
                            // ignore
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            restore();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        root.addView(sChip);

        // 隐藏状态用的 1×1 透明占位：只要它还在，窗口尺寸就不会变 0，
        // 系统那边的可见性状态机就不会被翻过去（见 hideAll 的注释）。
        sPlaceholder = new View(ctx);
        sPlaceholder.setLayoutParams(new LinearLayout.LayoutParams(1, 1));
        sPlaceholder.setVisibility(View.GONE);
        root.addView(sPlaceholder);

        // 拖动：按住顶部标题栏移动整个窗口
        bar.setOnTouchListener(new View.OnTouchListener() {
            private int downX;
            private int downY;
            private float touchX;
            private float touchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = sParams.x;
                        downY = sParams.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        sParams.x = downX + (int) (event.getRawX() - touchX);
                        sParams.y = downY + (int) (event.getRawY() - touchY);
                        try {
                            sWm.updateViewLayout(sRoot, sParams);
                        } catch (Throwable ignore) {
                            // ignore
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        return root;
    }

    /** 悬浮窗当前是否显示（音量键开关要用；已最小化成小方块也算显示）。 */
    static boolean isVisible() {
        return sRoot != null && !sHidden;
    }

    /** 短按音量减：显示/隐藏切换。 */
    static void toggle() {
        if (isVisible()) {
            hideAll();
        } else {
            showAll();
        }
    }

    /** 当前窗口是否可触摸（解锁窗口内为 true）。 */
    static boolean touchable() {
        return sTouchable;
    }

    /**
     * 切换窗口的可触摸性。
     *
     * 为什么要能切：不可触摸的窗口点不到也拖不动，用户偶尔需要拖动位置或点「复制」，
     * 所以给一个「按住音量减 5 秒解锁、20 秒后自动上锁」的窗口 ——
     * 对局中的常态永远是不可触摸（零遮挡标记），只有用户主动解锁的那段时间才可触摸。
     */
    private static void setTouchable(final boolean on) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    setTouchable(on);
                }
            });
            return;
        }
        if (sRoot == null || sParams == null) {
            return;
        }
        try {
            int flags = sParams.flags;
            if (on) {
                flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            if (flags == sParams.flags) {
                return;
            }
            sParams.flags = flags;
            sWm.updateViewLayout(sRoot, sParams);
            sTouchable = on;
            i.i(on ? "窗口已解锁触摸" : "窗口已上锁（不可触摸）");
        } catch (Throwable t) {
            i.w("切换触摸性失败：" + t);
        }
    }

    /** 解锁触摸 UNLOCK_SECONDS 秒，期间标题显示倒计时，到点自动上锁。 */
    static void unlockTouch() {
        MAIN.removeCallbacks(TICK);
        setTouchable(true);
        sUnlockLeft = UNLOCK_SECONDS;
        setTitleText("已解锁 " + sUnlockLeft + "s");
        MAIN.postDelayed(TICK, 1000L);
    }

    /** 立即上锁。 */
    static void lockTouch() {
        MAIN.removeCallbacks(TICK);
        sUnlockLeft = 0;
        setTouchable(false);
        setTitleText(TITLE);
    }

    /** 解锁倒计时；只在主线程跑。 */
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            sUnlockLeft--;
            if (sUnlockLeft <= 0) {
                lockTouch();
                return;
            }
            setTitleText("已解锁 " + sUnlockLeft + "s");
            MAIN.postDelayed(this, 1000L);
        }
    };

    private static void setTitleText(final String s) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sTitle != null) {
                        sTitle.setText(s);
                    }
                } catch (Throwable ignore) {
                    // ignore
                }
            }
        });
    }

    /**
     * 完全隐藏：把面板和小方块都藏起来，只留一个 1×1 的透明占位视图。
     *
     * 为什么必须留个占位、不能让内容整体 GONE（两种做法真机都踩过）：
     *   - removeView + addView：摘掉再挂回来，窗口会卡在 mDrawState=READY_TO_SHOW、
     *     Surface shown=false、alpha=0 —— dumpsys 里窗口在，屏幕上却没有；
     *   - 直接 setVisibility(GONE)：窗口尺寸被算成 0×0，系统随即把窗口的
     *     mPolicyVisibility 置 false，之后恢复 VISIBLE 也回不来
     *     （mEnterAnimationPending=true 卡住，surface 一直是 shown=false）。
     * 留一个 1×1 透明占位，窗口尺寸永远非零、可见性状态机不动，屏幕上同样零痕迹。
     */
    static void hideAll() {
        if (sRoot == null || sHidden) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    hideAll();
                }
            });
            return;
        }
        try {
            sPanel.setVisibility(View.GONE);
            sChip.setVisibility(View.GONE);
            sPlaceholder.setVisibility(View.VISIBLE);
            sHidden = true;
            i.i("悬浮窗已隐藏（按音量加恢复）");
        } catch (Throwable t) {
            i.e("隐藏悬浮窗失败", t);
        }
    }

    /** 重新挂上（音量加触发）。 */
    static void showAll() {
        if (sRoot == null || !sHidden) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    showAll();
                }
            });
            return;
        }
        try {
            sPlaceholder.setVisibility(View.GONE);
            if (sMinimized) {
                sChip.setVisibility(View.VISIBLE);
            } else {
                sPanel.setVisibility(View.VISIBLE);
            }
            sHidden = false;
            i.i("悬浮窗已显示（按音量减隐藏）");
        } catch (Throwable t) {
            i.e("显示悬浮窗失败", t);
        }
    }

    /** 最小化：整块面板收起，只留一枚「FJ」小方块（点它恢复）。 */
    private static void minimize() {
        try {
            sMinimized = true;
            sPanel.setVisibility(View.GONE);
            sChip.setVisibility(View.VISIBLE);
            i.i("悬浮窗已最小化：点「FJ」小方块可恢复");
        } catch (Throwable t) {
            i.e("最小化失败", t);
        }
    }

    /** 从「FJ」小方块恢复完整面板。 */
    private static void restore() {
        try {
            sMinimized = false;
            sChip.setVisibility(View.GONE);
            sPanel.setVisibility(View.VISIBLE);
            i.i("悬浮窗已从小方块恢复");
        } catch (Throwable t) {
            i.e("恢复悬浮窗失败", t);
        }
    }

    /** 扫描入口（音量加短按 / 面板按钮都走这里）。 */
    static void startScan() {
        if (sScanning) {
            return;
        }
        sScanning = true;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    sScanBtn.setEnabled(false);
                    sBody.setText("扫描中…");
                } catch (Throwable ignore) {
                    // ignore
                }
            }
        });
        i.i("触发扫描");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                c.Result r;
                try {
                    r = c.scanOnce();
                } catch (Throwable e) {
                    r = new c.Result();
                    r.error = "扫描崩溃：" + e;
                    i.e(r.error, e);
                }
                showResult(r);
                sScanning = false;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            sScanBtn.setEnabled(true);
                        } catch (Throwable ignore) {
                            // ignore
                        }
                    }
                });
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void showResult(final c.Result r) {
        final String[] lines = c.toLines(r);
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        if (lines.length == 0) {
            sb.append("未命中任何角色\n");
        }
        sb.append("---\n");
            sb.append("耗时 ").append(r.millis).append(" ms")
                    .append(" | 通道 ").append(r.reader)
                    .append(" | 区域 ").append(r.regions)
                .append(" | 读取 ").append(r.bytes / 1048576).append(" MB")
                .append(" | 命中 ").append(r.count).append("/12");
        // 命中来自哪个区域（root 版按区域分组、取角色最多的区域）；没有区域达标时
        // 退化成全局合并，这里把两种来源区分开，方便对着 logcat 排查。
        if (r.bestRegion != 0) {
            sb.append(" | 采信区域 0x").append(Long.toHexString(r.bestRegion));
        } else if (r.mergedCount > 0) {
            sb.append(" | 全局合并 ").append(r.mergedCount).append(" 个编号");
        }
        sb.append('\n');
        if (r.error != null) {
            sb.append("错误：").append(r.error).append('\n');
        }
        final String plain = sb.toString();
        i.i("扫描结果：\n" + plain);
        writeToFile(plain);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    sBody.setText(buildSpanned(r, plain));
                } catch (Throwable t) {
                    i.e("渲染结果失败", t);
                }
                toast("扫描完成：" + r.count + "/12（" + r.millis + " ms）");
            }
        });
    }

    private static CharSequence buildSpanned(c.Result r, String plain) {
        SpannableStringBuilder sb = new SpannableStringBuilder(plain);
        int pos = 0;
        for (int i = 0; i < 12; i++) {
            if (!r.found[i]) {
                continue;
            }
            String line = "编号" + (i + 1) + " : " + e.campName(r.camp[i]) + "丨"
                    + e.nameOf(r.role[i]);
            int start = plain.indexOf(line, pos);
            if (start < 0) {
                continue;
            }
            pos = start + line.length();
            int color;
            if (r.camp[i] == 1) {
                color = CAMP_COLOR_DETECTIVE;
            } else if (r.camp[i] == 2) {
                color = CAMP_COLOR_WOLF;
            } else {
                color = CAMP_COLOR_MYSTERY;
            }
            sb.setSpan(new ForegroundColorSpan(color), start, start + line.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** 复制结果到剪贴板（音量加长按 / 面板按钮）。 */
    static void copyText() {
        try {
            CharSequence txt = sBody.getText();
            ClipboardManager cm = (ClipboardManager) sCtx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("s", txt));
                toast("已复制");
            }
        } catch (Throwable t) {
            i.e("复制失败", t);
        }
    }

    /**
     * 把结果落盘。
     *
     * 只在 debug 构建里调用（见 showResult）：release 版本一行日志、一个文件都不落，
     * 免 root 的进程内读取本身不产生任何可被游戏侧观察到的痕迹。
     */
    private static void writeToFile(String text) {
        if (!i.ON) {
            return;
        }
        try {
            File dir = sCtx.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "log.txt");
            FileOutputStream fos = new FileOutputStream(f, false);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(text);
            w.close();
            i.i("结果已写入 " + f.getAbsolutePath());
        } catch (Throwable t) {
            i.e("写文件失败", t);
        }
    }

    private static void toast(final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(sCtx, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignore) {
                    // ignore
                }
            }
        });
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
