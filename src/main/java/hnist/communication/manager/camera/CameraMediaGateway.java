package hnist.communication.manager.camera;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/** MediaMTX API 快照与路径配置访问；API 不可达与视频源未就绪必须分开。 */
@Slf4j
@Component
public class CameraMediaGateway {
    /** MediaMTX tracks2 单轨信息：codec + 音频采样率/位深（供 WebRTC 支持性判定）。 */
    public record TrackInfo(String codec, Integer sampleRate, Integer bitDepth) {}
    public record PathState(boolean ready, List<String> tracks, List<TrackInfo> trackInfos, int readers) {}
    public record Snapshot(boolean available, Instant checkedAt, Map<String, PathState> paths) {}
    private final RestTemplate http;
    private final String api;
    private final CameraMediaProperties properties;
    private volatile Snapshot cached;

    public CameraMediaGateway(@Value("${mediamtx.api}") String api, CameraMediaProperties properties) {
        this.api = api.replaceAll("/+$", "");
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);
        factory.setReadTimeout(2000);
        this.http = new RestTemplate(factory);
    }

    public synchronized void invalidate() { cached = null; }

    @SuppressWarnings("unchecked")
    public synchronized Snapshot snapshot() {
        if (cached != null && cached.checkedAt().plusSeconds(2).isAfter(Instant.now())) return cached;
        Map<String, PathState> paths = new HashMap<>();
        try {
            for (int page = 0; page < 100; page++) {
                Map<String, Object> body = http.getForObject(api + "/v3/paths/list?itemsPerPage=100&page=" + page, Map.class);
                if (body == null || !(body.get("items") instanceof List<?> items)) throw new IllegalStateException();
                for (Object raw : items) {
                    Map<String, Object> item = (Map<String, Object>) raw;
                    List<String> tracks = item.get("tracks") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
                    List<TrackInfo> trackInfos = parseTrackInfos(item.get("tracks2"));
                    int readers = item.get("readers") instanceof List<?> list ? list.size() : 0;
                    paths.put(String.valueOf(item.get("name")),
                            new PathState(Boolean.TRUE.equals(item.get("ready")), tracks, trackInfos, readers));
                }
                int pages = body.get("pageCount") instanceof Number number ? number.intValue() : 1;
                if (page + 1 >= pages) {
                    return cached = new Snapshot(true, Instant.now(), Map.copyOf(paths));
                }
            }
            // 超过分页上限不返回不完整的“离线”结果。
            throw new IllegalStateException();
        } catch (Exception e) {
            // 查询失败返回 available=false 表示“未知”，不能用旧的 cached 冒充在线。
            log.warn("读取MediaMTX路径快照失败: {}", e.getMessage());
            return cached = new Snapshot(false, Instant.now(), Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    public String configuredTransport(String path) {
        try {
            Map<String, Object> config = http.getForObject(api + "/v3/config/paths/get/" + validPath(path), Map.class);
            if (config == null) return "unknown";
            return String.valueOf(config.getOrDefault("rtspTransport", config.getOrDefault("sourceProtocol", "unknown")));
        } catch (Exception e) {
            log.debug("读取MediaMTX传输配置失败: path={}, reason={}", path, e.getMessage());
            return "unknown";
        }
    }

    /** tracks2 形如 [{"codec":"H264",...},{"codec":"LPCM","codecProps":{"sampleRate":44100,"bitDepth":16,...}}]。 */
    @SuppressWarnings("unchecked")
    private static List<TrackInfo> parseTrackInfos(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<TrackInfo> infos = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            String codec = String.valueOf(((Map<String, Object>) map).get("codec"));
            Integer sampleRate = null, bitDepth = null;
            Object props = ((Map<String, Object>) map).get("codecProps");
            if (props instanceof Map<?, ?> p) {
                sampleRate = intOrNull(((Map<String, Object>) p).get("sampleRate"));
                bitDepth = intOrNull(((Map<String, Object>) p).get("bitDepth"));
            }
            infos.add(new TrackInfo(codec, sampleRate, bitDepth));
        }
        return List.copyOf(infos);
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    public String rtspUrl(String path) { return properties.getRtspBase().replaceAll("/+$", "") + "/" + validPath(path); }

    @SuppressWarnings("unchecked")
    public void ensurePublisher(String path) {
        String name = validPath(path);
        // 兼容输出路径必须已是 source=publisher 的专用路径；被其他来源占用时拒绝复用，只有路径不存在才创建。
        try {
            Map<String, Object> config = http.getForObject(api + "/v3/config/paths/get/" + name, Map.class);
            if (config == null || !"publisher".equals(config.get("source")))
                throw new hnist.communication.exception.BusinessException("兼容输出路径已被其他配置占用");
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.debug("兼容输出路径不存在，创建 publisher: path={}", name);
            http.postForObject(api + "/v3/config/paths/add/" + name, Map.of("source", "publisher"), String.class);
        }
    }

    public static String validPath(String path) {
        // 路径会拼进 MediaMTX URL，限制字符集防止路径穿越或查询串注入。
        if (path == null || !path.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*") || path.length() > 160)
            throw new hnist.communication.exception.BusinessException("播放路径只允许字母、数字、下划线、横线及目录分隔符");
        return path;
    }
}
