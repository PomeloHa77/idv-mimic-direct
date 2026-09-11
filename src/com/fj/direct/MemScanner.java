package com.fj.direct;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;

/**
 * 进程内内存扫描器（免 root 的核心）。
 *
 * 原理：本代码就跑在游戏进程里，所以读自己的内存不需要 root、也不需要 ptrace：
 *   1. /proc/self/maps       → 枚举可读写匿名区域
 *   2. /proc/self/mem        → 按块读取（未映射页会抛 EIO，跳过即可）
 *
 * 特征码与字段偏移完全沿用 root 版 模仿者遍历.cpp：
 *   [i]==105(i) [i+1]==100(d) [i+2]==120(x) [i+5]==99(c) [i+6]==97(a) [i+14]==105 [i+23]==105
 *   +0x3  内存编号 0-11（实际编号 1-12）
 *   +0xC  阵营 1=侦探团 2=狼人 3=神秘客
 *   +0x19 起 6 字节 identity，按阵营取第 0/1/2 字节作为单字节角色索引
 *
 * 相比原版修掉的两个问题：
 *   - 原版按 4096 整页扫描，跨页的特征码会漏；这里块间重叠 32 字节。
 *   - 原版 Name[i+23] 在 i 接近页尾时越界读栈；这里用 limit = n - 32 收敛。
 */
public final class MemScanner {

    public static final String TAG = "FJDirect";

    /** 单次读取块大小。 */
    private static final int CHUNK = 1 << 20;
    /** 特征码最大偏移（i+23 与 identity 的 i+0x1B 都在 32 字节内）。 */
    private static final int LOOKAHEAD = 32;
    /** 扫描字节上限，避免极端情况下长时间占用（默认 3 GB）。 */
    private static final long BYTE_BUDGET = 3L << 30;

    public static final class Result {
        public final boolean[] found = new boolean[12];
        public final int[] camp = new int[12];
        public final int[] role = new int[12];
        public int count;
        public long bytes;
        public int regions;
        public int hitPages;
        public long millis;
        public boolean memOk;
        public String error;
    }

    private MemScanner() {
    }

    public static Result scanOnce() {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        ArrayList<long[]> regs = readRegions();
        r.regions = regs.size();
        Log.i(TAG, "扫描开始：rw 匿名区域 " + regs.size() + " 个");

        RandomAccessFile mem;
        try {
            mem = new RandomAccessFile("/proc/self/mem", "r");
            r.memOk = true;
        } catch (Throwable t) {
            r.memOk = false;
            r.error = "打开 /proc/self/mem 失败：" + t;
            r.millis = System.currentTimeMillis() - t0;
            Log.e(TAG, r.error);
            return r;
        }

        byte[] buf = new byte[CHUNK + LOOKAHEAD];
        try {
            int unreadable = 0;
            for (int ri = 0; ri < regs.size(); ri++) {
                long[] reg = regs.get(ri);
                long addr = reg[0];
                long end = reg[1];
                while (addr < end) {
                    if (r.bytes >= BYTE_BUDGET) {
                        r.error = "达到扫描上限 " + BYTE_BUDGET + " 字节，已提前结束";
                        break;
                    }
                    long remain = end - addr;
                    int want = (int) Math.min((long) CHUNK + LOOKAHEAD, remain);
                    if (want <= LOOKAHEAD) {
                        break;
                    }
                    int n;
                    try {
                        mem.seek(addr);
                        n = mem.read(buf, 0, want);
                    } catch (Throwable t) {
                        n = -1;
                    }
                    if (n <= 0) {
                        // 该地址不可读（未驻留 / EIO / 已释放），按页跳过
                        unreadable++;
                        addr += 4096L;
                        continue;
                    }
                    r.bytes += n;
                    scanBuffer(buf, n, r);
                    if (r.count >= 12) {
                        break;
                    }
                    addr += CHUNK;
                }
                if (r.count >= 12 || r.bytes >= BYTE_BUDGET) {
                    break;
                }
            }
            if (unreadable > 0) {
                Log.i(TAG, "不可读页跳过次数：" + unreadable);
            }
        } catch (Throwable t) {
            r.error = "扫描异常：" + t;
            Log.e(TAG, r.error, t);
        } finally {
            try {
                mem.close();
            } catch (Throwable ignore) {
                // ignore
            }
        }

        r.millis = System.currentTimeMillis() - t0;
        Log.i(TAG, "扫描结束：命中编号 " + r.count + " 个，读取 " + r.bytes
                + " 字节，耗时 " + r.millis + " ms");
        return r;
    }

    private static void scanBuffer(byte[] b, int n, Result r) {
        int limit = n - LOOKAHEAD;
        for (int i = 0; i < limit; i++) {
            if (b[i] != 105) {
                continue;
            }
            if (b[i + 1] != 100 || b[i + 2] != 120) {
                continue;
            }
            if (b[i + 5] != 99 || b[i + 6] != 97) {
                continue;
            }
            if (b[i + 14] != 105 || b[i + 23] != 105) {
                continue;
            }
            int camp = b[i + 0xC] & 0xff;
            if (camp < 1 || camp > 3) {
                continue;
            }
            int idx = b[i + 3] & 0xff;
            if (idx > 11) {
                continue;
            }
            int role = b[i + 0x19 + (camp - 1)] & 0xff;
            // 侦探团 / 神秘客 的索引 0 表示未初始化；狼人的 0 合法（普通狼）
            if (role == 0 && camp != 2) {
                continue;
            }
            if (!r.found[idx]) {
                r.found[idx] = true;
                r.camp[idx] = camp;
                r.role[idx] = role;
                r.count++;
                Log.i(TAG, "命中 编号" + (idx + 1) + " 阵营" + camp + " 角色" + role);
            }
        }
    }

    private static ArrayList<long[]> readRegions() {
        ArrayList<long[]> out = new ArrayList<long[]>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            while ((line = br.readLine()) != null) {
                int sp1 = line.indexOf(' ');
                if (sp1 <= 0) {
                    continue;
                }
                int sp2 = line.indexOf(' ', sp1 + 1);
                if (sp2 <= 0) {
                    continue;
                }
                String perms = line.substring(sp1 + 1, sp2);
                if (perms.length() < 2 || perms.charAt(0) != 'r' || perms.charAt(1) != 'w') {
                    continue;
                }
                // 只取匿名区域：无 pathname 或 [anon:...]
                String rest = line.substring(sp2 + 1);
                int sp3 = rest.indexOf(' ');
                if (sp3 <= 0) {
                    continue;
                }
                String rest2 = rest.substring(sp3 + 1);
                int sp4 = rest2.indexOf(' ');
                if (sp4 <= 0) {
                    continue;
                }
                String rest3 = rest2.substring(sp4 + 1);
                int sp5 = rest3.indexOf(' ');
                String path = sp5 < 0 ? "" : rest3.substring(sp5 + 1).trim();
                if (!path.isEmpty() && !path.startsWith("[anon")) {
                    continue;
                }
                String range = line.substring(0, sp1);
                int dash = range.indexOf('-');
                if (dash <= 0) {
                    continue;
                }
                long start = Long.parseLong(range.substring(0, dash), 16);
                long end = Long.parseLong(range.substring(dash + 1), 16);
                if (end - start < 4096) {
                    continue;
                }
                out.add(new long[] { start, end });
            }
        } catch (Throwable t) {
            Log.e(TAG, "读取 /proc/self/maps 失败", t);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignore) {
                    // ignore
                }
            }
        }
        return out;
    }

    public static String[] toLines(Result r) {
        String[] out = new String[12];
        int n = 0;
        for (int i = 0; i < 12; i++) {
            if (r.found[i]) {
                out[n++] = "编号" + (i + 1) + " : " + RoleTable.campName(r.camp[i]) + "丨"
                        + RoleTable.nameOf(r.role[i]);
            }
        }
        String[] real = new String[n];
        System.arraycopy(out, 0, real, 0, n);
        return real;
    }
}
