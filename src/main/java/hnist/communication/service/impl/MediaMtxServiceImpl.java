package hnist.communication.service.impl;

import hnist.communication.exception.BusinessException;
import hnist.communication.service.MediaMtxService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

/** MediaMTX REST 客户端：维护流路径配置并生成播放/推流地址。 */
@Slf4j
@Service
public class MediaMtxServiceImpl implements MediaMtxService {

    /** MediaMTX 流路径前缀 */
    private static final String PATH_PREFIX = "cam_";

    /** 媒体端口（与 MediaMTX 配置一致） */
    private static final int WEBRTC_PORT = 8889;
    private static final int HLS_PORT = 8888;
    private static final int RTMP_PORT = 1935;

    private final RestTemplate restTemplate;
    /** 后端本机访问 MediaMTX 的 API 地址（MediaMTX 与后端同机部署时保持 localhost） */
    private final String apiBase;
    /** 兜底 base：正常由客户端请求 Host 自动推导，仅在非请求上下文时使用（yml 可留空） */
    private final String webrtcBase;
    private final String hlsBase;
    private final String rtmpBase;

    public MediaMtxServiceImpl(RestTemplate restTemplate,
                               @Value("${mediamtx.api}") String apiBase,
                               @Value("${mediamtx.webrtc.base:}") String webrtcBase,
                               @Value("${mediamtx.hls.base:}") String hlsBase,
                               @Value("${mediamtx.rtmp.base:}") String rtmpBase) {
        this.restTemplate = restTemplate;
        this.apiBase = apiBase;
        this.webrtcBase = webrtcBase;
        this.hlsBase = hlsBase;
        this.rtmpBase = rtmpBase;
    }

    @Override
    public String addPath(Long cameraId, String rtspUrl, String sourceProtocol) {
        String pathName = PATH_PREFIX + cameraId;

        String protocol = normalizeSourceProtocol(sourceProtocol);

        Map<String, Object> body = Map.of(
                "source", rtspUrl,
                "sourceOnDemand", false,
                "rtspTransport", protocol
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        try {
            restTemplate.postForObject(apiBase + "/v3/config/paths/add/" + pathName, req, String.class);
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            // 只有响应明确提到 rtspTransport 才降级为旧字段 sourceProtocol；
            // 其他 400 可能代表地址/配置本身有误，直接失败避免掩盖问题。
            if (!e.getResponseBodyAsString().contains("rtspTransport")) throw new BusinessException("流媒体配置被拒绝");
            try {
                restTemplate.postForObject(apiBase + "/v3/config/paths/add/" + pathName,
                        new HttpEntity<>(Map.of("source", rtspUrl, "sourceOnDemand", false, "sourceProtocol", protocol), headers), String.class);
            } catch (Exception fallbackFailure) {
                log.warn("MediaMTX旧字段兼容创建失败: path={}, reason={}", pathName, fallbackFailure.getMessage());
                throw new BusinessException("流媒体服务不可用");
            }
        } catch (Exception e) {
            log.warn("MediaMTX创建路径失败: path={}, reason={}", pathName, e.getMessage());
            throw new BusinessException("流媒体服务不可用");
        }
        return pathName;
    }

    @Override
    public String ensurePath(Long cameraId, String rtspUrl, String sourceProtocol) {
        String pathName = PATH_PREFIX + cameraId;
        try {
            if (isConfiguredPathCurrent(pathName, rtspUrl, normalizeSourceProtocol(sourceProtocol))) return pathName;
            // 只在配置确实变化时重建，管理接口异常不能触发破坏性恢复。
            removePath(pathName);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // 路径尚不存在属于正常情况，继续创建。
            log.debug("MediaMTX路径尚不存在，准备创建: {}", pathName);
        } catch (Exception e) {
            // 管理接口异常时拒绝重建，避免把仍在使用的流配置误删。
            log.warn("MediaMTX管理接口不可用，拒绝重建路径: path={}, reason={}", pathName, e.getMessage());
            throw new BusinessException("流媒体管理接口不可用，未修改现有流配置");
        }
        return addPath(cameraId, rtspUrl, sourceProtocol);
    }

    @Override
    public String recreatePath(Long cameraId, String rtspUrl, String sourceProtocol) {
        String pathName = PATH_PREFIX + cameraId;
        removePath(pathName);
        return addPath(cameraId, rtspUrl, sourceProtocol);
    }

    @Override
    public void removePath(String pathName) {
        try {
            restTemplate.delete(apiBase + "/v3/config/paths/delete/" + pathName);
        } catch (Exception e) {
            // 删除失败只告警：MediaMTX 不可用不应阻断数据库侧删除摄像头。
            log.warn("MediaMTX删除流失败: {}", pathName, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isSourceReady(String pathName) {
        try {
            Map<String, Object> resp = restTemplate.getForObject(
                    apiBase + "/v3/paths/list", Map.class);
            return pathReady(resp, pathName);
        } catch (Exception e) {
            // 查询失败按未就绪处理，调用方会继续等待，不会误报可播放。
            log.warn("MediaMTX查询流状态失败: {}", pathName, e);
            return false;
        }
    }

    /** 检查 MediaMTX 配置中的路径、源地址和传输协议是否都是当前值。 */
    @SuppressWarnings("unchecked")
    private boolean isConfiguredPathCurrent(String pathName, String rtspUrl, String sourceProtocol) {
        Map<String, Object> config = restTemplate.getForObject(apiBase + "/v3/config/paths/get/" + pathName, Map.class);
        if (config == null) throw new IllegalStateException("配置接口返回空响应");
        return rtspUrl.equals(config.get("source")) && sourceProtocol.equals(String.valueOf(
                config.getOrDefault("rtspTransport", config.getOrDefault("sourceProtocol", "automatic"))));
    }

    private String normalizeSourceProtocol(String sourceProtocol) {
        if (sourceProtocol == null) return "automatic";
        String value = sourceProtocol.trim().toLowerCase();
        return switch (value) {
            case "tcp", "udp", "multicast" -> value;
            default -> "automatic";
        };
    }

    @SuppressWarnings("unchecked")
    private boolean pathReady(Map<String, Object> resp, String pathName) {
        if (resp == null || resp.get("items") == null) {
            return false;
        }
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("items");
        for (Map<String, Object> item : items) {
            if (pathName.equals(item.get("name"))) {
                return Boolean.TRUE.equals(item.get("ready"));
            }
        }
        return false;
    }

    @Override
    public String buildWebrtcUrl(String pathName) {
        String base = resolveHostBase();
        if (base != null) {
            return withPort(base, WEBRTC_PORT) + "/" + pathName;
        }
        if (webrtcBase != null && !webrtcBase.isBlank()) {
            return webrtcBase + "/" + pathName;
        }
        log.warn("buildWebrtcUrl: 无法解析请求 Host，返回 localhost 兜底");
        return "http://localhost:" + WEBRTC_PORT + "/" + pathName;
    }

    @Override
    public String buildHlsUrl(String pathName) {
        String base = resolveHostBase();
        if (base != null) {
            return withPort(base, HLS_PORT) + "/" + pathName + "/index.m3u8";
        }
        if (hlsBase != null && !hlsBase.isBlank()) {
            return hlsBase + "/" + pathName + "/index.m3u8";
        }
        log.warn("buildHlsUrl: 无法解析请求 Host，返回 localhost 兜底");
        return "http://localhost:" + HLS_PORT + "/" + pathName + "/index.m3u8";
    }

    @Override
    public String buildRtmpPushUrl(String pathName) {
        String host = resolveHost();
        if (host != null) {
            return "rtmp://" + withPort(host, RTMP_PORT) + "/" + pathName;
        }
        if (rtmpBase != null && !rtmpBase.isBlank()) {
            return rtmpBase + "/" + pathName;
        }
        log.warn("buildRtmpPushUrl: 无法解析请求 Host，返回 localhost 兜底");
        return "rtmp://localhost:" + RTMP_PORT + "/" + pathName;
    }

    /** WebRTC(WHIP) 推流地址：复用 WHEP 的 WEBRTC_PORT，避免 RTMP 解复用带来的秒级延迟。 */
    @Override
    public String buildWhipPushUrl(String pathName) {
        return buildWebrtcUrl(pathName) + "/whip";
    }

    // ---- 辅助方法 ----

    /** 从当前 HTTP 请求推导外部可访问的 http(s) base（兼容反向代理的 X-Forwarded-*） */
    private String resolveHostBase() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest req = attrs.getRequest();
        String fwdHost = req.getHeader("X-Forwarded-Host");
        String fwdProto = req.getHeader("X-Forwarded-Proto");
        String host = (fwdHost != null && !fwdHost.isBlank()) ? fwdHost.trim() : req.getServerName();
        String proto = (fwdProto != null && !fwdProto.isBlank()) ? fwdProto.trim() : req.getScheme();
        return proto + "://" + host;
    }

    /** 仅取 host（不含协议），供 rtmp:// 使用 */
    private String resolveHost() {
        String base = resolveHostBase();
        if (base == null) {
            return null;
        }
        int idx = base.indexOf("://");
        return idx >= 0 ? base.substring(idx + 3) : base;
    }

    /**
     * 拼接媒体端口：请求 Host 可能带后端端口（如 8080），必须替换成 MediaMTX 端口，
     * 否则生成的播放/推流地址会指向后端而不是 8889/8888/1935。
     */
    private String withPort(String authority, int port) {
        String prefix = "";
        String rest = authority;
        int idx = authority.indexOf("://");
        if (idx >= 0) {
            prefix = authority.substring(0, idx + 3);
            rest = authority.substring(idx + 3);
        }
        // rest 形如 "host" 或 "host:port"
        int colon = rest.lastIndexOf(':');
        String host = colon >= 0 ? rest.substring(0, colon) : rest;
        if (host.isEmpty()) {
            return authority;
        }
        return prefix + host + ":" + port;
    }
}
