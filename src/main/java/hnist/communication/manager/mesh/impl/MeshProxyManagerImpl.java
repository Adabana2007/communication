package hnist.communication.manager.mesh.impl;

import hnist.communication.exception.BusinessException;
import hnist.communication.manager.mesh.MeshProxyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 通过受控白名单与内网地址校验，代理手机无法直连的 Mesh 节点。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeshProxyManagerImpl implements MeshProxyManager {

    private static final Set<String> ALLOWED_PATHS = new HashSet<>(Arrays.asList(
            "cgi_read_allnode", "cgi_read_nodeinfo", "cgi_read_topo",
            "cgi_write_nodeinfo", "cgi_spectral_scan"
    ));

    private final RestTemplate restTemplate;

    @Override
    public Object proxy(String ip, String path, String scheme, Integer port, Map<String, String[]> parameters) {
        // path 必须命中 ALLOWED_PATHS，目标 IP 另由 isPrivateIpv4 限定私有网段，防止代理被用于 SSRF。
        if (!isPrivateIpv4(ip)) {
            throw new BusinessException("非法的内网地址");
        }
        if (!ALLOWED_PATHS.contains(path)) {
            throw new BusinessException("不支持的 mesh 接口");
        }
        String normalizedScheme = scheme == null ? "http" : scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            throw new BusinessException("Mesh 接入协议只支持 http 或 https");
        }
        if (port != null && (port < 1 || port > 65535)) {
            throw new BusinessException("Mesh 接入端口不合法");
        }

        String query = buildForwardQuery(parameters);
        String authority = ip + (port == null ? "" : ":" + port);
        String target = normalizedScheme + "://" + authority + "/cgi-bin/" + path
                + (query.isEmpty() ? "" : "?" + query);
        log.info("[MeshProxy] 转发: {}", target);

        try {
            return restTemplate.getForObject(URI.create(target), Object.class);
        } catch (RestClientException e) {
            log.warn("[MeshProxy] mesh 设备不可达: {} ({})", ip, e.getMessage());
            throw new BusinessException("Mesh 设备不可达: " + authority + "（" + connectionReason(e) + "）");
        } catch (IllegalArgumentException e) {
            throw new BusinessException("非法的 mesh 请求地址");
        }
    }

    /** 仅允许私有 IPv4（10/8、172.16/12、192.168/16），防 SSRF 访问公网。 */
    private boolean isPrivateIpv4(String ip) {
        if (ip == null || !ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return false;
        }
        String[] parts = ip.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        for (String part : parts) {
            int value = Integer.parseInt(part);
            if (value < 0 || value > 255) return false;
        }
        if (first == 10) return true;
        if (first == 172 && second >= 16 && second <= 31) return true;
        return first == 192 && second == 168;
    }

    /** 透传除 ip/path/scheme/port 外的查询参数（空格编码为 %20，与前端 encodeURIComponent 一致）。 */
    private String buildForwardQuery(Map<String, String[]> parameters) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
            String key = entry.getKey();
            if ("ip".equals(key) || "path".equals(key)
                    || "scheme".equals(key) || "port".equals(key)) continue;
            for (String value : entry.getValue()) {
                if (query.length() > 0) query.append('&');
                query.append(encode(key)).append('=').append(encode(value == null ? "" : value));
            }
        }
        return query.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String connectionReason(RestClientException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("timed out") || message.contains("timeout")) return "连接超时";
        if (message.contains("refused")) return "连接被拒绝，请检查端口";
        if (message.contains("no route") || message.contains("unreachable")) return "服务器没有到该网段的路由";
        if (message.contains("certificate") || message.contains("ssl")) return "HTTPS 证书校验失败";
        return "请检查服务器路由、协议和端口";
    }
}
