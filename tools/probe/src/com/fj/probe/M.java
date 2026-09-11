package com.fj.probe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 遮挡探针（dev-only）。
 *
 * 要回答的问题只有一个：把悬浮窗设成 FLAG_NOT_TOUCHABLE 之后，
 * 下层窗口（= 游戏的窗口）收到的触摸事件还有没有 FLAG_WINDOW_IS_OBSCURED？
 *
 * 测法：
 *   1. 点「NOT 不可触摸」建一个不可触摸的悬浮窗盖在屏幕中间；
 *   2. adb shell su -c "input tap 540 1170"（落点就在悬浮窗内部）；
 *   3. 看本 Activity 收到的 flags —— 期望 0（没有 OBSCURED 位）；
 *   4. 换成「可触摸」再点一次：事件会被悬浮窗吃掉，本 Activity 收不到新事件。
 *
 * 事件同时写 logcat（tag=FJPROBE）与屏幕，屏幕上的字号特意放大，方便截图存档。
 */
public class M extends Activity {

    private static final String TAG = "FJPROBE";
    /** FLAG_WINDOW_IS_OBSCURED（API 21 起公开，这里写死避免踩 SDK 差异） */
    private static final int FLAG_OBSCURED = 0x1;
    /** Android 12 的 FLAG_WINDOW_IS_PARTIALLY_OBSCURED */
    private static final int FLAG_PARTIAL = 0x2;

    private TextView mOut;
    private WindowManager mWm;
    private View mOv;
    private WindowManager.LayoutParams mP;
    /** 0=不可触摸 1=可触摸（只在这两个状态间切，避免 removeView+addView 那个坑）。 */
    private int mMode;

    private Button mBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // 按钮放最上面：位置固定，方便用 adb input tap 精确点到
        mBtn = new Button(this);
        mBtn.setText("悬浮窗：不可触摸");
        mBtn.setTextSize(20f);
        mBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mMode = 1 - mMode;
                applyMode();
            }
        });
        root.addView(mBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 220));

        mOut = new TextView(this);
        mOut.setTextColor(Color.WHITE);
        mOut.setTextSize(22f);
        mOut.setText("点按钮建悬浮窗，再用 adb input tap 540 1170 点屏幕中间\n");
        root.addView(mOut);

        setContentView(root);
        mWm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        buildOverlay();
        Log.i(TAG, "probe 启动");
    }

    /**
     * 只挂一次窗口，之后只用 updateViewLayout 切 flag。
     *
     * 为什么不 removeViewImmediate + addView：真机踩过 —— 摘掉再挂回去，窗口会卡在
     * mDrawState=READY_TO_SHOW / inputConfig=NOT_VISIBLE，屏幕上没有、输入侧也不参与
     * 命中测试，测出来的「无 OBSCURED」是假的（因为压根没有窗口参与）。
     */
    private void buildOverlay() {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        mP = new WindowManager.LayoutParams(400, 400, type, 0, PixelFormat.TRANSLUCENT);
        mP.gravity = Gravity.TOP | Gravity.START;
        mP.x = 340;
        mP.y = 970;

        TextView t = new TextView(this);
        t.setText("PROBE");
        t.setTextColor(Color.WHITE);
        t.setTextSize(28f);
        mOv = t;
        try {
            mWm.addView(mOv, mP);
            say("已挂上悬浮窗，覆盖 340,970 - 740,1370\n");
        } catch (Throwable e) {
            say("addView 失败：" + e + "\n");
            mOv = null;
        }
        applyMode();
    }

    /** 切换可触摸 / 不可触摸，并把状态打到屏幕与 logcat（便于对拍 dumpsys input）。 */
    private void applyMode() {
        if (mOv == null) {
            return;
        }
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (mMode == 0) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        mP.flags = flags;
        try {
            mWm.updateViewLayout(mOv, mP);
        } catch (Throwable e) {
            say("updateViewLayout 失败：" + e + "\n");
            return;
        }
        mOv.setBackgroundColor(mMode == 0 ? 0x88204080 : 0x88A02020);
        mBtn.setText(mMode == 0 ? "悬浮窗：不可触摸" : "悬浮窗：可触摸");
        String line = "模式=" + (mMode == 0 ? "NOT_TOUCHABLE" : "TOUCHABLE")
                + " flags=0x" + Integer.toHexString(mP.flags) + " isShown=" + mOv.isShown();
        Log.i(TAG, line);
        say(line + "\n");
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int f = e.getFlags();
        String verdict = ((f & FLAG_OBSCURED) != 0) ? "有 OBSCURED" : "无 OBSCURED";
        if ((f & FLAG_PARTIAL) != 0) {
            verdict += " + 有 PARTIAL";
        }
        String line = "tap action=" + e.getActionMasked() + " x=" + (int) e.getX()
                + " y=" + (int) e.getY() + " flags=0x" + Integer.toHexString(f) + " → " + verdict;
        Log.i(TAG, line);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            say(line + "\n");
        }
        return super.dispatchTouchEvent(e);
    }

    private void say(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mOut.append(s);
            }
        });
    }
}
