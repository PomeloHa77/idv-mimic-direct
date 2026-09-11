package z.a;

import java.nio.ByteBuffer;

/**
 * 进程内读内存的统一入口（原 com.fj.direct.MemReader）：优先走 native 的
 * process_vm_readv，退回 /proc/self/mem。
 *
 * 为什么要分两条路：
 *   * Android 10+ 的 SELinux 明确拒绝 untrusted_app 打开 /proc/self/mem
 *     （真机日志：open failed: EACCES），所以文件路径在新系统上必然失败；
 *   * process_vm_readv 读「自己」在内核里走 same_thread_group 快速放行、不经过 LSM，
 *     所以免 root 可用 —— 这是整个免 root 方案的关键（见 src/native/nrt.c）。
 *
 * native 侧不用 `Java_包名_类名_方法名` 那种导出符号（名字本身就是特征），
 * 改成 JNI_OnLoad + RegisterNatives 动态绑定（见 src/native/nrt.c），
 * 所以 libnrt.so 的动态符号表里只剩一个 JNI_OnLoad。
 *
 * native 库加载失败时（比如换了 ABI）自动降级到文件路径，不会让扫描器直接崩掉。
 *
 * 为什么 native 版写进 ByteBuffer（direct）而不是 byte[]：用 byte[] 就得
 * GetPrimitiveArrayCritical，那段区域里 ART 不允许 GC —— 我们一连读 3.5 GB，
 * 等于把整个游戏进程的 GC 按住十几秒，游戏侧的表现就是持续掉帧。
 * direct buffer 是堆外内存，native 直接写，完全不碰 GC。
 */
final class d {

    private static final String LIB = "nrt";

    private static boolean ok;
    private static String err;

    static {
        try {
            System.loadLibrary(LIB);
            ok = true;
            i.i("读取通道：native 已挂载");
        } catch (Throwable t) {
            ok = false;
            err = t.toString();
            i.w("读取通道：native 挂载失败，退回文件读取：" + t);
        }
    }

    private d() {
    }

    /** native 通道是否可用。 */
    static boolean nativeOk() {
        return ok;
    }

    static String nativeErr() {
        return err;
    }

    /**
     * 从当前进程读出 [addr, addr+len) 到 dst[off..]（由 nrt.c 的 JNI_OnLoad 绑定）。
     *
     * @return >=0 实际读到的字节数；<0 为 -errno（-EFAULT=未映射，-EPERM=被拒）
     */
    static native int a(long addr, ByteBuffer dst, int off, int len);
}
