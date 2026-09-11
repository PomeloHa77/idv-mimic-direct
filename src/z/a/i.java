package z.a;

import android.util.Log;

/**
 * 日志门面（原各处直接调用的 Log.i/w/e）。
 *
 * 为什么必须收口：我们的代码就跑在游戏进程里、和游戏同一个 UID，日志是「同一个
 * 进程」发出的 —— 游戏侧一句 `logcat -d` 就能读到自己进程的这些行，tag 和文案
 * 本身就是特征。所以 release 构建把 ON 编成 false（build.ps1 会改写这一行），
 * javac 随即把 `if (ON) {...}` 整块消掉：dex 里没有日志调用、logcat 一行不写。
 *
 * 排错时用 `pwsh -File build.ps1 -DebugBuild` 构建，ON=true，日志照常输出。
 * （参数不叫 -Debug：PowerShell 的通用参数里已有 -Debug，会直接报 MetadataError。）
 */
public final class i {

    /** 构建期开关：build.ps1 会把这里改成 true（-DebugBuild）或 false（默认）。 */
    public static final boolean ON = false;

    /** 日志 tag：中性两字，避免任何品牌特征。 */
    private static final String T = "nt";

    private i() {
    }

    public static void i(String m) {
        if (ON) {
            Log.i(T, m);
        }
    }

    public static void w(String m) {
        if (ON) {
            Log.w(T, m);
        }
    }

    public static void e(String m) {
        if (ON) {
            Log.e(T, m);
        }
    }

    public static void e(String m, Throwable t) {
        if (ON) {
            Log.e(T, m, t);
        }
    }
}
