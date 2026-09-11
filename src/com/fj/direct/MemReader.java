package com.fj.direct;

import android.util.Log;

/**
 * 进程内读内存的统一入口：优先走 native 的 process_vm_readv，退回 /proc/self/mem。
 *
 * 为什么要分两条路：
 *   * Android 10+ 的 SELinux 明确拒绝 untrusted_app 打开 /proc/self/mem
 *     （真机日志：open failed: EACCES），所以文件路径在新系统上必然失败；
 *   * process_vm_readv 读「自己」在内核里走 same_thread_group 快速放行、不经过 LSM，
 *     所以免 root 可用 —— 这是整个免 root 方案的关键（见 src/native/mmread.c）。
 *
 * native 库加载失败时（比如换了 ABI）自动降级到文件路径，不会让扫描器直接崩掉。
 */
final class MemReader {

    private static final String LIB = "mmread";

    private static boolean nativeOk;
    private static String nativeErr;

    static {
        try {
            System.loadLibrary(LIB);
            nativeOk = true;
            Log.i(MemScanner.TAG, "MemReader: lib" + LIB + ".so 已加载，用 process_vm_readv 自读");
        } catch (Throwable t) {
            nativeOk = false;
            nativeErr = t.toString();
            Log.w(MemScanner.TAG, "MemReader: lib" + LIB + ".so 加载失败，退回 /proc/self/mem：" + t);
        }
    }

    private MemReader() {
    }

    /** native 通道是否可用。 */
    static boolean nativeOk() {
        return nativeOk;
    }

    static String nativeErr() {
        return nativeErr;
    }

    /**
     * 从当前进程读出 [addr, addr+len) 到 dst[off..]。
     *
     * @return >=0 实际读到的字节数；<0 为 -errno（-EFAULT=未映射，-EPERM=被拒）
     */
    static native int readSelf(long addr, byte[] dst, int off, int len);
}
