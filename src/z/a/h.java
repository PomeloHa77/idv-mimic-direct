package z.a;

/**
 * 字符串解密（原「品牌字面量」全部改成密文后，唯一能解出原文的地方）。
 *
 * 为什么要做这件事：dex 里的字符串常量池是可以被静态扫描的 —— 只要出现
 * 「模仿者」「FJDirect」「com/fj/direct」这类字面量，任何一个懂行的人拖进 jadx
 * 就能一眼看出这是个什么工具。所以构建时由 tools/obf_strings.py 把源码里所有
 * 字符串字面量改写成这里的密文数组（源码本身保持可读，产物只在 work/ 下）。
 *
 * 密钥的**唯一真源**是本文件的 K：tools/obf_strings.py 会来解析它，
 * 两边不一致时构建直接失败（见 build.ps1 的解码自检）。
 */
public final class h {

    /** 16 字节滚动密钥（改了它必须重跑构建，脚本会自动读取这里）。 */
    private static final byte[] K = {
        (byte) 0xEE, (byte) 0xB7, (byte) 0xA2, (byte) 0x72,
        (byte) 0xB5, (byte) 0xD8, (byte) 0x3B, (byte) 0x21,
        (byte) 0xCC, (byte) 0x2A, (byte) 0xF9, (byte) 0x2C,
        (byte) 0xCE, (byte) 0xCA, (byte) 0x75, (byte) 0x82,
    };

    private h() {
    }

    /**
     * 解出字符串。
     *
     * 编码面（Python）与解码面（这里）必须逐字节对称：
     *   c[i] = p[i] ^ K[(i * 5 + 7) & 15]
     * 构建时会拿全部密文在 JVM 上跑一遍这个函数，和源码原文逐条比对。
     */
    public static String a(byte[] c) {
        if (c == null) {
            return null;
        }
        byte[] p = new byte[c.length];
        for (int i = 0; i < c.length; i++) {
            p[i] = (byte) (c[i] ^ K[(i * 5 + 7) & 15]);
        }
        try {
            return new String(p, "UTF-8");
        } catch (Throwable t) {
            return new String(p);
        }
    }
}
