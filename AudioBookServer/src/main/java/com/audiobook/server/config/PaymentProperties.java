package com.audiobook.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付网关配置。
 *
 * 密钥类配置一律不写死在 yml 里，全部走环境变量占位符（见 application.yml），
 * 生产环境通过环境变量注入；未注入时各 Provider 的 isConfigured() 返回 false，
 * 系统自动降级为 MOCK 模拟支付，保证本地开发/演示环境开箱可用。
 */
@Component
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    /**
     * 服务端对外可访问的基础地址，用于拼接支付平台异步通知回调地址。
     * 必须是支付平台能访问到的公网地址（本地联调需内网穿透），否则回调永远收不到。
     */
    private String notifyBaseUrl = "http://localhost:8080";

    /** 支付完成后浏览器跳转回的前端页面（仅支付宝网页支付使用） */
    private String returnUrl = "http://localhost:3000/credits";

    /** 订单未支付自动关闭的时长（分钟） */
    private int orderExpireMinutes = 30;

    /**
     * 是否允许 MOCK 模拟支付。
     * 默认 true（开发友好）；生产环境务必显式设为 false，否则任何人都能凭
     * 「模拟支付」接口白拿积分。CreditOrderService 在 mock 入账前会强校验该开关。
     */
    private boolean allowMock = false;

    private Alipay alipay = new Alipay();
    private Wechat wechat = new Wechat();

    public static class Alipay {
        /** 支付宝分配的应用 ID */
        private String appId = "";
        /** 商户应用私钥（PKCS8，Base64，无 PEM 头尾），用于请求签名 */
        private String privateKey = "";
        /** 支付宝公钥（Base64，无 PEM 头尾），用于验证异步通知签名 */
        private String publicKey = "";
        /** 网关地址；沙箱为 https://openapi-sandbox.dl.alipaydev.com/gateway.do */
        private String gatewayUrl = "https://openapi.alipay.com/gateway.do";

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
        public String getGatewayUrl() { return gatewayUrl; }
        public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }
    }

    public static class Wechat {
        /** 公众号/小程序/应用 APPID */
        private String appId = "";
        /** 商户号 */
        private String mchId = "";
        /** 商户 API 证书序列号 */
        private String serialNo = "";
        /** 商户 API 私钥（PKCS8，Base64，无 PEM 头尾） */
        private String privateKey = "";
        /** APIv3 密钥（32 位），用于解密回调报文 */
        private String apiV3Key = "";
        /** 微信支付平台证书公钥（Base64，无 PEM 头尾），用于验证回调签名 */
        private String platformPublicKey = "";
        /** 接口基础地址 */
        private String apiUrl = "https://api.mch.weixin.qq.com";

        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getMchId() { return mchId; }
        public void setMchId(String mchId) { this.mchId = mchId; }
        public String getSerialNo() { return serialNo; }
        public void setSerialNo(String serialNo) { this.serialNo = serialNo; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getApiV3Key() { return apiV3Key; }
        public void setApiV3Key(String apiV3Key) { this.apiV3Key = apiV3Key; }
        public String getPlatformPublicKey() { return platformPublicKey; }
        public void setPlatformPublicKey(String platformPublicKey) { this.platformPublicKey = platformPublicKey; }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    }

    public String getNotifyBaseUrl() { return notifyBaseUrl; }
    public void setNotifyBaseUrl(String notifyBaseUrl) { this.notifyBaseUrl = notifyBaseUrl; }
    public String getReturnUrl() { return returnUrl; }
    public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
    public int getOrderExpireMinutes() { return orderExpireMinutes; }
    public void setOrderExpireMinutes(int orderExpireMinutes) { this.orderExpireMinutes = orderExpireMinutes; }
    public boolean isAllowMock() { return allowMock; }
    public void setAllowMock(boolean allowMock) { this.allowMock = allowMock; }
    public Alipay getAlipay() { return alipay; }
    public void setAlipay(Alipay alipay) { this.alipay = alipay; }
    public Wechat getWechat() { return wechat; }
    public void setWechat(Wechat wechat) { this.wechat = wechat; }
}
