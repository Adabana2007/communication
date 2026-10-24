package hnist.communication.util;

import java.util.regex.Pattern;

/** 对手机号、邮箱等个人敏感信息做展示脱敏。 */
public final class SensitiveDataMasker {

    private static final String MASKED_SECRET = "******";
    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://)([^/?#\\s]*@)");
    private static final Pattern URL_SECRET_QUERY = Pattern.compile(
            "(?i)([?&](?:password|passwd|pwd|secret|token|access_token|auth)=)[^&#\\s]*");

    private SensitiveDataMasker() {
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        if (phone.length() >= 7) {
            return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        }
        if (phone.length() >= 3) {
            return phone.charAt(0) + "*".repeat(phone.length() - 2) + phone.charAt(phone.length() - 1);
        }
        return "*".repeat(phone.length());
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email.charAt(0) + "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** 密码、密钥等不可逆展示的凭据统一替换为固定掩码，同时保留“已配置/未配置”的语义。 */
    public static String maskSecret(String secret) {
        return secret == null || secret.isBlank() ? secret : MASKED_SECRET;
    }

    /** 脱敏 URL 中的 Basic 凭据与常见密钥查询参数，避免 RTSP 地址回显账号密码。 */
    public static String maskUrlCredentials(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String withoutUserInfo = URL_USER_INFO.matcher(url)
                .replaceAll(match -> match.group(1) + MASKED_SECRET + "@");
        return URL_SECRET_QUERY.matcher(withoutUserInfo)
                .replaceAll(match -> match.group(1) + MASKED_SECRET);
    }
}
