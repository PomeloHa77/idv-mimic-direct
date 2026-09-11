package com.fj.direct;

import android.content.pm.Signature;
import android.util.Base64;

/**
 * 官方签名回填。
 *
 * 重打包必然换签名，而网易 SDK（ntunisdk / mpay / UniFix 热更新 / 渠道判定）会把
 * PackageInfo.signatures 上报服务器校验，签名一变就登录失败。这里把原包的官方证书
 * （从 META-INF/H55_KEYS.RSA 提取的 X.509 DER，SHA256
 * 918e39b4e77e4e1e03a7c0236c6f473037851069c67b5ebecf60e9b9744e4dc9）直接返回，
 * 使 toCharsString()/toByteArray()/MD5/SHA1 等一切派生值都与官方一致。
 *
 * smali 侧把所有 `PackageInfo->signatures` 的读取替换成 SigFix.sigs()。
 */
public final class SigFix {

    private static final String OFFICIAL_CERT_B64 =
            "MIIDSTCCAjGgAwIBAgIEdY1R1TANBgkqhkiG9w0BAQsFADBUMQswCQYDVQQGEwJjbjELMAkGA1UECBMCemoxCzAJBgNVBAcT"
          + "Amh6MQ0wCwYDVQQKEwRkd3JnMQ0wCwYDVQQLEwRkd3JnMQ0wCwYDVQQDEwRkd3JnMCAXDTE3MTExMzEwNDYwMloYDzIwNzIw"
          + "ODE2MTA0NjAyWjBUMQswCQYDVQQGEwJjbjELMAkGA1UECBMCemoxCzAJBgNVBAcTAmh6MQ0wCwYDVQQKEwRkd3JnMQ0wCwYD"
          + "VQQLEwRkd3JnMQ0wCwYDVQQDEwRkd3JnMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAh3nyUWHEqSScC2RPMWL/"
          + "u3bHXSmjgiJSsb/9ndBtuXDMIT0TzcJHDiqgyhf7Ix8KcBVdEt0uuMxCMUQCr3vJRb1PKkR+lWM1snen1MBboKcAk3t96gEM"
          + "u0tv0xSngiOL8J7Cyp6/eaYNmC8qIzgvJdEej9QDAL+wowPFIATXWsQtb6OhtFJAJppRSh3GngqEMoDZQ5zP0oElkQJf37jE"
          + "YLIK4z74xTSIZ1SXgpnLUJ0Pp92B2ZWVR7MejygK8P5hVpk+z5ODOPxQ7sjlQdCFnZPWm29IP1kqKcaFTAJE4nLaho3zhQM1"
          + "/URwVLCWKdoQvDyQuxFX5yGKNSrBRXQOwQIDAQABoyEwHzAdBgNVHQ4EFgQUBWV9az34IUfetFc3uiKU+XvYfJ8wDQYJKoZI"
          + "hvcNAQELBQADggEBAEgDwqnFegxZ5KEimUFg20PzVs9t6/lmVEhCZZiN1ReTgN4I8lWqFFIX8gSA4YqEn8bUEAen/k/0eLe0"
          + "t36x4XQkNBvmLsTAF8XnZZB0R/IqaJUp7y2khOv3TqpK7yzphsZZ55rcS4jO27PVj2Fqw6ji+okEbK943hxDk8krX74BdWFG"
          + "l+l9IJB0T+Idbvzplid2zZ/xNAZRaZdBXt/H4ZE9WtUiFhSUCsAUMqdk94T1iKCA6imdU1l/2ZuSmVoW/whwYH8skSVHYa/BC"
          + "e5cUf9s+pvrEUOAXj/L94EVnMZ8YCOTGwDFDeNJyfeGqN8C/vdFgXvUg81zls1Ll4v1nug=";

    private static volatile Signature[] sCache;

    private SigFix() {
    }

    /** 供 smali 补丁替换 PackageInfo->signatures 使用。 */
    public static Signature[] sigs() {
        Signature[] s = sCache;
        if (s == null) {
            synchronized (SigFix.class) {
                s = sCache;
                if (s == null) {
                    byte[] der = Base64.decode(OFFICIAL_CERT_B64, Base64.DEFAULT);
                    s = new Signature[] { new Signature(der) };
                    sCache = s;
                }
            }
        }
        return s;
    }

    /** 官方签名 hex 串（等价 Signature.toCharsString()，排错用）。 */
    public static String charsString() {
        return sigs()[0].toCharsString();
    }
}
