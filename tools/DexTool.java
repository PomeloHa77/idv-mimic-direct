import brut.androlib.src.SmaliBuilder;
import brut.androlib.src.SmaliDecoder;
import brut.directory.ExtFile;

import java.io.File;

/**
 * 极简 smali/baksmali 驱动：直接复用 apktool 内置的 dexlib2/smali 实现，
 * 避免依赖已从 Maven Central 下架的 com.android.tools.smali 独立 jar。
 *
 * 用法：
 *   d <apk文件> <输出smali目录> <dex条目名> [apiLevel]   反编译单个 dex
 *   a <smali目录> <输出dex文件> [apiLevel]               回编译 smali
 */
public final class DexTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: d <apk> <outSmaliDir> <dexEntry> [api] | a <smaliDir> <outDex> [api]");
            System.exit(2);
        }
        String mode = args[0];
        if ("d".equals(mode)) {
            String dexEntry = args.length > 3 ? args[3] : "classes.dex";
            int api = args.length > 4 ? Integer.parseInt(args[4]) : 21;
            // decode(apkFile, outDir, dexFileName, bakDeb, apiLevel)
            SmaliDecoder.decode(new File(args[1]), new File(args[2]), dexEntry, false, api);
            System.out.println("baksmali ok: " + args[2]);
        } else if ("a".equals(mode)) {
            int api = args.length > 3 ? Integer.parseInt(args[3]) : 21;
            File out = new File(args[2]);
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            // build(smaliDir, outDexFile, apiLevel)
            SmaliBuilder.build(new ExtFile(args[1]), out, api);
            System.out.println("smali ok: " + out.getAbsolutePath() + " size=" + out.length());
        } else {
            System.err.println("unknown mode: " + mode);
            System.exit(2);
        }
    }
}
