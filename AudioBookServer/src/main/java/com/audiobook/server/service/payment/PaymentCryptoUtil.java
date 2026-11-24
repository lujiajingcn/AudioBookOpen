package com.audiobook.server.service.payment;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 支付签名/加解密工具，只用 JDK 自带能力实现，不引入支付宝/微信 SDK。
 *
 * 为什么不引 SDK：两家 SDK 会带进一堆传递依赖（HTTP 客户端、JSON 库、日志桥接），
 * 与本项目已有的 WebClient / Jackson 版本容易冲突；而这里实际只需要
 * 「SHA256withRSA 签名/验签」和「AES-256-GCM 解密」三个原语，JDK 完全够用。
 */
public final class PaymentCryptoUtil {

    private PaymentCryptoUtil() {
    }

    /** 加载 PKCS8 格式私钥（传入 Base64 内容，可含 PEM 头尾与换行） */
    public static PrivateKey loadPrivateKey(String base64) throws Exception {
        byte[] der = Base64.getDecoder().decode(cleanPem(base64));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** 加载 X509 格式公钥（传入 Base64 内容，可含 PEM 头尾与换行） */
    public static PublicKey loadPublicKey(String base64) throws Exception {
        byte[] der = Base64.getDecoder().decode(cleanPem(base64));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** SHA256withRSA 签名，返回 Base64 */
    public static String sign(String content, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    /** SHA256withRSA 验签 */
    public static boolean verify(String content, String signBase64, PublicKey publicKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return signature.verify(Base64.getDecoder().decode(signBase64));
    }

    /**
     * 微信支付 APIv3 回调报文解密（AES-256-GCM）。
     *
     * @param apiV3Key       商户 APIv3 密钥（32 字节）
     * @param associatedData resource.associated_data（可能为 null）
     * @param nonce          resource.nonce
     * @param ciphertext     resource.ciphertext（Base64）
     */
    public static String aesGcmDecrypt(String apiV3Key, String associatedData, String nonce, String ciphertext)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
        // 认证标签固定 128 位，nonce 由微信下发（12 字节）
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        if (associatedData != null && !associatedData.isEmpty()) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 去掉 PEM 头尾与所有空白字符，只留 Base64 主体 */
    private static String cleanPem(String pem) {
        if (pem == null) {
            return "";
        }
        return pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }
}
