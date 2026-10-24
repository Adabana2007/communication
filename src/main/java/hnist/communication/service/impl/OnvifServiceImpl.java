package hnist.communication.service.impl;

import hnist.communication.exception.BusinessException;
import hnist.communication.service.OnvifService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import java.io.StringReader;

/**
 * ONVIF 协议客户端，通过 SOAP + HTTP Digest 认证获取摄像头 RTSP 地址
 */
@Slf4j
@Service
public class OnvifServiceImpl implements OnvifService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** HTTP 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT = 5000;
    /** HTTP 读取超时（毫秒） */
    private static final int READ_TIMEOUT = 5000;
    private static final Pattern DIGEST_PARAM = Pattern.compile("([a-zA-Z]+)=(\"[^\"]*\"|[^,]+)");

    @Override
    public String getRtspUrl(String ip, int port, String username, String password) {
        try {
            String deviceUrl = "http://" + ip + ":" + port + "/onvif/device_service";

            // 1. GetServices → 获取 Media Service 地址。部分设备此步骤也要求认证。
            String servicesResp = sendOnvif(deviceUrl,
                    "<GetServices xmlns=\"http://www.onvif.org/ver10/device/wsdl\"><IncludeCapability>false</IncludeCapability></GetServices>",
                    username, password);

            String mediaUrl = findMediaServiceUrl(servicesResp);
            if (mediaUrl == null) {
                // 部分设备 GetServices 不返回媒体地址，按同路径 media_service 兜底。
                mediaUrl = deviceUrl.replace("device_service", "media_service");
            }

            // 2. GetProfiles → 获取 Profile Token
            String profilesResp = sendOnvif(mediaUrl,
                    "<GetProfiles xmlns=\"http://www.onvif.org/ver10/media/wsdl\" />",
                    username, password);

            String token = chooseProfileToken(profilesResp);
            if (token == null || token.isBlank()) {
                throw new IOException("ONVIF 未返回可用的视频 Profile");
            }

            // 3. GetStreamUri → 获取 RTSP URL
            String streamBody = """
                    <GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl">
                        <ProfileToken>%s</ProfileToken>
                        <StreamSetup>
                            <Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream>
                            <Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport>
                        </StreamSetup>
                    </GetStreamUri>""".formatted(token);

            String uri = findElementText(sendOnvif(mediaUrl, streamBody, username, password), "Uri");
            return uri != null && (uri.startsWith("rtsp://") || uri.startsWith("rtsps://")) ? uri : null;

        } catch (Exception e) {
            // 任何探测异常都返回 null，由调用方决定提示手动填写还是保留原地址。
            log.error("ONVIF获取RTSP地址失败: {}:{}", ip, port, e);
            return null;
        }
    }

    /** 发送 SOAP 请求。username/password 不为 null 时走 HTTP Digest 认证 */
    private String sendOnvif(String urlStr, String soapBody, String username, String password) throws IOException {
        String soap = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body>"
                + soapBody + "</s:Body></s:Envelope>";
        byte[] body = soap.getBytes(StandardCharsets.UTF_8);

        // 首次请求：不带认证头
        HttpURLConnection conn = buildConnection(urlStr, body, null);
        try {
            int status = conn.getResponseCode();
            if (status == 200) {
                return readBody(conn);
            }

            // 401 → Digest 认证握手
            if (status == 401 && username != null && password != null) {
                String challenge = conn.getHeaderField("WWW-Authenticate");
                if (challenge != null) {
                    String auth = challenge.regionMatches(true, 0, "Digest", 0, 6)
                            ? buildDigestAuth(challenge, username, password, "POST", parseUrl(urlStr).getPath())
                            : challenge.regionMatches(true, 0, "Basic", 0, 5)
                            ? "Basic " + Base64.getEncoder().encodeToString(
                                    (username + ":" + password).getBytes(StandardCharsets.UTF_8))
                            : null;
                    if (auth == null) throw new IOException("摄像头返回了不支持的认证方式");
                    HttpURLConnection authenticated = buildConnection(urlStr, body, auth);
                    try {
                        status = authenticated.getResponseCode();
                        if (status == 200) {
                            return readBody(authenticated);
                        }
                    } finally {
                        authenticated.disconnect();
                    }
                }
            }
            throw new IOException("ONVIF 请求失败: HTTP " + status);
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection buildConnection(String urlStr, byte[] body, String authHeader) throws IOException {
        URL url = parseUrl(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        return conn;
    }

    private static URL parseUrl(String urlStr) throws IOException {
        try {
            return URI.create(urlStr).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("ONVIF 地址格式非法", e);
        }
    }

    private String readBody(HttpURLConnection conn) throws IOException {
        try (InputStream is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 根据 WWW-Authenticate: Digest 头构建 Authorization 头 */
    private String buildDigestAuth(String challenge, String username, String password,
                                   String method, String uri) {
        Map<String, String> params = parseDigestParams(challenge);
        String realm = params.get("realm");
        String nonce = params.get("nonce");
        String qopRaw = params.getOrDefault("qop", "auth");
        String qop = qopRaw.toLowerCase().contains("auth") ? "auth" : qopRaw.split(",")[0].trim();

        String nc = "00000001";
        String cnonce = Base64.getEncoder().encodeToString(randomBytes(16));

        String ha1 = md5Hex(username + ":" + realm + ":" + password);
        String ha2 = md5Hex(method + ":" + uri);
        String response = md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);

        String auth = String.format(
                "Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", qop=%s, nc=%s, cnonce=\"%s\", response=\"%s\", algorithm=MD5",
                username, realm, nonce, uri, qop, nc, cnonce, response);
        if (params.get("opaque") != null) auth += ", opaque=\"" + params.get("opaque") + "\"";
        return auth;
    }

    /** 解析 Digest 参数，格式: key="value", key=value, ... */
    private Map<String, String> parseDigestParams(String header) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher matcher = DIGEST_PARAM.matcher(header.substring(7).trim());
        while (matcher.find()) {
            String value = matcher.group(2).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            map.put(matcher.group(1), value);
        }
        return map;
    }

    private String md5Hex(String input) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException("MD5 计算失败", e);
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    // ====== XML 解析辅助方法（按 localName，不依赖厂商使用 tt/trt/ns1 等具体前缀） ======

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // 禁用 DOCTYPE 与外部实体，防止摄像头返回的 XML 触发 XXE。
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private String findMediaServiceUrl(String xml) throws Exception {
        NodeList services = parseXml(xml).getElementsByTagNameNS("*", "Service");
        for (int i = 0; i < services.getLength(); i++) {
            Element service = (Element) services.item(i);
            String namespace = childText(service, "Namespace");
            if (namespace != null && namespace.toLowerCase().contains("/media/")) {
                String address = childText(service, "XAddr");
                if (address != null && !address.isBlank()) return address.trim();
            }
        }
        return null;
    }

    /** 优先 H.264 Profile，避免首个 Profile 是 H.265 主码流而浏览器 WebRTC 无法解码。 */
    private String chooseProfileToken(String xml) throws Exception {
        NodeList profiles = parseXml(xml).getElementsByTagNameNS("*", "Profiles");
        String first = null;
        for (int i = 0; i < profiles.getLength(); i++) {
            Element profile = (Element) profiles.item(i);
            String token = profile.getAttribute("token");
            if (token == null || token.isBlank()) continue;
            if (first == null) first = token;
            String encoding = descendantText(profile, "Encoding");
            if (encoding != null && encoding.replace(".", "").equalsIgnoreCase("H264")) return token;
        }
        return first;
    }

    private String findElementText(String xml, String localName) throws Exception {
        NodeList nodes = parseXml(xml).getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private String childText(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (localName.equals(node.getLocalName())) return node.getTextContent();
        }
        return null;
    }

    private String descendantText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }
}
