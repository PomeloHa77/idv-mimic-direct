package com.fj.direct;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
    private static TextView sBody;
    private static Button sScanBtn;
    private static WindowManager.LayoutParams sParams;
    private static volatile boolean sScanning;
    private static int sAttempts;

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
            Log.i(TAG, "悬浮窗已创建");
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
        root.setBackgroundColor(0xCC101010);
        int pad = dp(ctx, 8);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("模仿者·直装");
        title.setTextColor(0xFFDDDDDD);
        title.setTextSize(12f);
        title.setPadding(0, 0, dp(ctx, 8), 0);
        bar.addView(title);

        sScanBtn = new Button(ctx);
        sScanBtn.setText("扫描");
        sScanBtn.setTextSize(12f);
        sScanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        bar.addView(sScanBtn);

        Button copyBtn = new Button(ctx);
        copyBtn.setText("复制");
        copyBtn.setTextSize(12f);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText();
            }
        });
        bar.addView(copyBtn);

        Button hideBtn = new Button(ctx);
        hideBtn.setText("收起");
        hideBtn.setTextSize(12f);
        hideBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sBody.getVisibility() == View.VISIBLE) {
                    sBody.setVisibility(View.GONE);
                    ((Button) v).setText("展开");
                } else {
                    sBody.setVisibility(View.VISIBLE);
                    ((Button) v).setText("收起");
                }
            }
        });
        bar.addView(hideBtn);
        root.addView(bar);

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(ctx, 250), dp(ctx, 240));
        scroll.setLayoutParams(slp);

        sBody = new TextView(ctx);
        sBody.setTextColor(0xFFEEEEEE);
        sBody.setTextSize(13f);
        sBody.setText("点「扫描」开始（进对局后再点）");
        sBody.setPadding(0, dp(ctx, 6), 0, 0);
        scroll.addView(sBody);
        root.addView(scroll);

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
