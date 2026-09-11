package z.a;


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
 *
 * 输出策略也对齐原版：按 rw-anon 区域分组统计，最后取「命中编号最多的那个区域」
 * 作为结果（原版是 roleCount 降序取模块）；没有任何区域集齐 5 个编号时，
 * 退回「全局按编号去重取首个命中」，宁可少报也不空手。
 */
public final class c {


    /** 单次读取块大小。 */
    private static final int CHUNK = 1 << 20;
    /** 特征码最大偏移（i+23 与 identity 的 i+0x1B 都在 32 字节内）。 */
    private static final int LOOKAHEAD = 32;
    /** 扫描字节上限，避免极端情况下长时间占用（默认 3 GB）。 */
    /**
     * 单次扫描的读取上限。真机实测：Redmi K20 Pro（Android 13）上 rw 匿名区域共 1182 个，
     * 读满 3 GB 只用 7 s（process_vm_readv 通道），3 GB 会在扫完之前就被截断，
     * 有可能把真正存角色的区域漏在后面，所以放宽到 8 GB。
     */
    private static final long BYTE_BUDGET = 8L << 30;

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
        /** 实际用的读内存通道：process_vm_readv 或 /proc/self/mem */
        public String reader = "";
        /** 最终采信的区域起始地址（0 = 没有采信任何区域，用的是全局合并） */
        public long bestRegion;
        /** 全局合并命中的编号个数，仅用于诊断 */
        public int mergedCount;
        public String error;
    }

    /**
     * 单个 maps 区域内的命中统计。
     *
     * 为什么要按区域分组：角色数据是游戏一次性创建的一整块对象（12 个人一份表），
     * 但进程里同时存在上一局残留、序列化副本等干扰数据。root 版 模仿者遍历.cpp
     * 的做法就是「按 rw-anon 区域分组，取角色数最多的那个区域输出」，
     * 这里对齐同一策略：区域内按编号去重取首个命中，最后整体取命中编号最多的区域。
     */
    private static final class RegionStat {
        final long start;
        final boolean[] found = new boolean[12];
        final int[] camp = new int[12];
        final int[] role = new int[12];
        int count;

        RegionStat(long start) {
            this.start = start;
        }
    }

    private c() {
    }

    public static Result scanOnce() {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        ArrayList<long[]> regs = readRegions();
        r.regions = regs.size();
        i.i("扫描开始：rw 匿名区域 " + regs.size() + " 个");

        // 优先 native（process_vm_readv 自读，Android 10+ 也能用），
        // 退回 /proc/self/mem（Android 9 及以下可用，10+ 会被 SELinux 拒）。
        RandomAccessFile mem = null;
        boolean useNative = d.nativeOk();
        if (useNative) {
            r.memOk = true;
            r.reader = "process_vm_readv";
        } else {
            try {
                mem = new RandomAccessFile("/proc/self/mem", "r");
                r.memOk = true;
                r.reader = "/proc/self/mem";
            } catch (Throwable t) {
                r.memOk = false;
                r.error = "打开 /proc/self/mem 失败，且 native 通道不可用（"
                        + d.nativeErr() + "）：" + t;
                r.millis = System.currentTimeMillis() - t0;
                i.e(r.error);
                return r;
            }
        }

        byte[] buf = new byte[CHUNK + LOOKAHEAD];
        ArrayList<RegionStat> stats = new ArrayList<RegionStat>(regs.size());
        try {
            int unreadable = 0;
            boolean full = false;
            for (int ri = 0; ri < regs.size(); ri++) {
                long[] reg = regs.get(ri);
                long addr = reg[0];
                long end = reg[1];
                RegionStat stat = new RegionStat(addr);
                stats.add(stat);
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
                        if (useNative) {
                            n = d.a(addr, buf, 0, want);
                            if (n < 0) {
                                n = 0;          // -errno：该段不可读，按页跳过
                            }
                        } else {
                            mem.seek(addr);
                            n = mem.read(buf, 0, want);
                        }
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
                    scanBuffer(buf, n, stat);
                    if (stat.count >= 12) {
                        // 这个区域 12 个编号齐全，不可能有更好的了，直接收工
                        full = true;
                        break;
                    }
                    addr += CHUNK;
                }
                if (full || r.bytes >= BYTE_BUDGET) {
                    break;
                }
            }
            if (unreadable > 0) {
                i.i("不可读页跳过次数：" + unreadable);
            }

            // 取「命中编号最多」的区域（root 版的 roleCount 排序策略）。
            RegionStat best = null;
            for (int i = 0; i < stats.size(); i++) {
                RegionStat s = stats.get(i);
                if (best == null || s.count > best.count) {
                    best = s;
                }
            }
            if (best != null && best.count > 0) {
                i.i("最佳区域 0x" + Long.toHexString(best.start) + " 命中编号 "
                        + best.count + "/12（共 " + stats.size() + " 个区域）");
            }
            // 兜底：没有任何区域集齐 5 个编号时（比如对局刚开始、数据还没写全），
            // 用「全局按编号去重取首个命中」的结果，宁可少报也不要空手。
            if (best != null && best.count >= 5) {
                r.bestRegion = best.start;
                for (int i = 0; i < 12; i++) {
                    r.found[i] = best.found[i];
                    r.camp[i] = best.camp[i];
                    r.role[i] = best.role[i];
                }
                r.count = best.count;
            } else {
                r.bestRegion = 0;
                for (int i = 0; i < stats.size(); i++) {
                    RegionStat s = stats.get(i);
                    for (int k = 0; k < 12; k++) {
                        if (s.found[k] && !r.found[k]) {
                            r.found[k] = true;
                            r.camp[k] = s.camp[k];
                            r.role[k] = s.role[k];
                            r.count++;
                        }
                    }
                }
            }
            // 诊断用：全局按编号去重后一共认出几个编号
            boolean[] seen = new boolean[12];
            for (int i = 0; i < stats.size(); i++) {
                RegionStat s = stats.get(i);
                for (int k = 0; k < 12; k++) {
                    if (s.found[k]) {
                        seen[k] = true;
                    }
                }
            }
            for (int k = 0; k < 12; k++) {
                if (seen[k]) {
                    r.mergedCount++;
                }
            }
        } catch (Throwable t) {
            r.error = "扫描异常：" + t;
            i.e(r.error, t);
        } finally {
            if (mem != null) {
                try {
                    mem.close();
                } catch (Throwable ignore) {
                    // ignore
                }
            }
        }

        r.millis = System.currentTimeMillis() - t0;
        i.i("扫描结束：" + r.reader + " 通道，命中编号 " + r.count + " 个，读取 " + r.bytes
                + " 字节，耗时 " + r.millis + " ms");
        return r;
    }

    /** 在 buf[0..n) 里找特征码，命中写入该区域自己的统计（区域内按编号去重，首个命中为准）。 */
    private static void scanBuffer(byte[] b, int n, RegionStat r) {
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
            i.e("读取 /proc/self/maps 失败", t);
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
                out[n++] = "编号" + (i + 1) + " : " + e.campName(r.camp[i]) + "丨"
                        + e.nameOf(r.role[i]);
            }
        }
        String[] real = new String[n];
        System.arraycopy(out, 0, real, 0, n);
        return real;
    }
}
