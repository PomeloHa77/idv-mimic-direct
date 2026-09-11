package com.fj.direct;

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
import android.util.Log;
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
 * 悬浮窗：手动点「扫描」按钮触发一次进程内扫描，结果按阵营上色显示。
 *
 * 清单里自带 android.permission.SYSTEM_ALERT_WINDOW，所以只需要用户在系统设置里
 * 授权一次「显示在其他应用上层」；未授权时退化为 Toast + 写文件。
 */
public final class OverlayWindow {

    private static final String TAG = "FJDirect";
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
    private static WindowManager.LayoutParams sParams;
    private static volatile boolean sScanning;
    /** 已被音量减键摘下来（窗口不在 WindowManager 里）。 */
    private static volatile boolean sHidden;
    /** 面板被「✕」收成了小方块。 */
    private static volatile boolean sMinimized;
    private static int sAttempts;
    /** 「点」与「拖」的分界：位移超过这么多 px 就算拖动，不再当成点击。 */
    private static final int TAP_SLOP = 12;

    private OverlayWindow() {
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
                Log.w(TAG, "context 为空，无法建悬浮窗");
                return;
            }
            sWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (sWm == null) {
                Log.w(TAG, "WindowManager 为空");
                return;
            }
            sRoot = buildView(ctx);
            sParams = buildParams();
            sWm.addView(sRoot, sParams);
            Log.i(TAG, "悬浮窗已创建（悬浮窗权限=" + (overlayAllowed(ctx) ? "已授权" : "未授权") + "）");
            // 未授权时 addView **不会抛异常**，窗口会进 WindowManager 但被策略隐藏
            // （mPolicyVisibility=false / mAppOpVisibility=false），屏幕上什么都看不到。
            // 所以光靠 try/catch 判断不出「装了但没显示」，必须显式查一下 app-op 并提示。
            if (!overlayAllowed(ctx)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW 未授权：窗口已加入但会被系统隐藏");
                toast("悬浮窗被系统拦住：请到「设置 → 应用 → 显示在其他应用上层」允许后重开游戏");
            }
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (sRoot != null && !sHidden && !sRoot.isShown()) {
                            Log.w(TAG, "窗口已加入但未显示（被系统策略隐藏），多半是没授权「显示在其他应用上层」");
                            toast("悬浮窗没显示出来：请授权「显示在其他应用上层」后重开游戏");
                        }
                    } catch (Throwable ignore) {
                        // ignore
                    }
                }
            }, 3000L);
        } catch (Throwable t) {
            sRoot = null;
            Log.e(TAG, "创建悬浮窗失败（可能未授予 SYSTEM_ALERT_WINDOW）：" + t);
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
        title.setText("模仿者·直装");
        title.setTextColor(0xFFDDDDDD);
        title.setTextSize(12f);
        title.setPadding(0, 0, dp(ctx, 4), 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

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
        sBody.setText("点「扫描」开始（进对局后再点）\n"
                + "拖标题栏移动 ·「收起」只留按钮行 ·「✕」收成小方块\n"
                + "音量减=隐藏悬浮窗，音量加=显示");
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
            Log.i(TAG, "悬浮窗已隐藏（按音量加恢复）");
        } catch (Throwable t) {
            Log.e(TAG, "隐藏悬浮窗失败", t);
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
            Log.i(TAG, "悬浮窗已显示（按音量减隐藏）");
        } catch (Throwable t) {
            Log.e(TAG, "显示悬浮窗失败", t);
        }
    }

    /** 最小化：整块面板收起，只留一枚「FJ」小方块（点它恢复）。 */
    private static void minimize() {
        try {
            sMinimized = true;
            sPanel.setVisibility(View.GONE);
            sChip.setVisibility(View.VISIBLE);
            Log.i(TAG, "悬浮窗已最小化：点「FJ」小方块可恢复");
        } catch (Throwable t) {
            Log.e(TAG, "最小化失败", t);
        }
    }

    /** 从「FJ」小方块恢复完整面板。 */
    private static void restore() {
        try {
            sMinimized = false;
            sChip.setVisibility(View.GONE);
            sPanel.setVisibility(View.VISIBLE);
            Log.i(TAG, "悬浮窗已从小方块恢复");
        } catch (Throwable t) {
            Log.e(TAG, "恢复悬浮窗失败", t);
        }
    }

    private static void startScan() {
        if (sScanning) {
            return;
        }
        sScanning = true;
        sScanBtn.setEnabled(false);
        sBody.setText("扫描中…");
        Log.i(TAG, "用户触发扫描");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                MemScanner.Result r;
                try {
                    r = MemScanner.scanOnce();
                } catch (Throwable e) {
                    r = new MemScanner.Result();
                    r.error = "扫描崩溃：" + e;
                    Log.e(TAG, r.error, e);
                }
                showResult(r);
                sScanning = false;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        sScanBtn.setEnabled(true);
                    }
                });
            }
        }, "FJDirect-scan");
        t.setDaemon(true);
        t.start();
    }

    private static void showResult(final MemScanner.Result r) {
        final String[] lines = MemScanner.toLines(r);
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
        Log.i(TAG, "扫描结果：\n" + plain);
        writeToFile(plain);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    sBody.setText(buildSpanned(r, plain));
                } catch (Throwable t) {
                    Log.e(TAG, "渲染结果失败", t);
                }
                toast("扫描完成：" + r.count + "/12（" + r.millis + " ms）");
            }
        });
    }

    private static CharSequence buildSpanned(MemScanner.Result r, String plain) {
        SpannableStringBuilder sb = new SpannableStringBuilder(plain);
        int pos = 0;
        for (int i = 0; i < 12; i++) {
            if (!r.found[i]) {
                continue;
            }
            String line = "编号" + (i + 1) + " : " + RoleTable.campName(r.camp[i]) + "丨"
                    + RoleTable.nameOf(r.role[i]);
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

    private static void copyText() {
        try {
            CharSequence txt = sBody.getText();
            ClipboardManager cm = (ClipboardManager) sCtx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("FJDirect", txt));
                toast("已复制");
            }
        } catch (Throwable t) {
            Log.e(TAG, "复制失败", t);
        }
    }

    private static void writeToFile(String text) {
        try {
            File dir = sCtx.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "scan.txt");
            FileOutputStream fos = new FileOutputStream(f, false);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(text);
            w.close();
            Log.i(TAG, "结果已写入 " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "写文件失败", t);
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
