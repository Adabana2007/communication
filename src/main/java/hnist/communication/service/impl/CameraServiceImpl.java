package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import hnist.communication.manager.camera.*;
import hnist.communication.exception.BusinessException;
import hnist.communication.dto.CameraRequest;
import hnist.communication.entity.Camera;
import hnist.communication.mapper.CameraMapper;
import hnist.communication.service.CameraService;
import hnist.communication.service.MediaMtxService;
import hnist.communication.service.OnvifService;
import hnist.communication.vo.CameraVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.net.URI;
import java.net.URISyntaxException;

/** 摄像头接入与播放编排：ONVIF 解析 RTSP 并在 MediaMTX 维护流路径。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CameraServiceImpl implements CameraService {

    private static final int DEFAULT_ONVIF_PORT = 80;

    private final CameraMapper cameraMapper;
    private final OnvifService onvifService;
    private final MediaMtxService mediaMtxService;
    private final CameraMediaGateway mediaGateway;
    private final CameraDiagnostics diagnostics;
    private final CameraCompatibility compatibility;
    private final CameraMediaProperties mediaProperties;

    @Override
    @Transactional
    public CameraVO addCamera(CameraRequest req) {
        validateNewCamera(req);
        validatePlayback(req);
        if ("compatible".equals(req.getPlaybackMode())) throw new BusinessException("请先按原始路径添加摄像头并诊断，再启用兼容输出");
        // 同一 IP 只允许一条记录：先查重，避免同一设备被接入后生成多个 MediaMTX 路径。
        if (cameraMapper.selectCount(
                new LambdaQueryWrapper<Camera>().eq(Camera::getIp, req.getIp())) > 0) {
            throw new BusinessException("该IP已添加");
        }

        // 手动填写的 RTSP 优先（现场通常是浏览器兼容的 H.264 子码流）；未填写才走 ONVIF，
        // 探测异常也降级为 null，由下方统一提示手动填写，避免直接 500。
        String rtspUrl = req.getRtspUrl();
        if (rtspUrl == null || rtspUrl.isBlank()) {
            rtspUrl = onvifService.getRtspUrl(
                    req.getIp(),
                    req.getPort() != null ? req.getPort() : DEFAULT_ONVIF_PORT,
                    req.getUsername(),
                    req.getPassword());
        }
        if (rtspUrl == null) {
            throw new BusinessException("ONVIF获取失败，请手动填写RTSP地址");
        }
        rtspUrl = normalizeRtspUrl(rtspUrl);

        int port = req.getPort() != null ? req.getPort() : DEFAULT_ONVIF_PORT;
        Camera camera = Camera.builder()
                .name(req.getName())
                .deviceCode(req.getDeviceCode())
                .deviceModel(req.getDeviceModel())
                .ip(req.getIp())
                .port(port)
                .username(req.getUsername())
                .password(req.getPassword())
                .brand(req.getBrand())
                .rtspUrl(rtspUrl)
                .sourceProtocol(normalizeSourceProtocol(req.getSourceProtocol()))
                .snapshotUrl(req.getSnapshotUrl())
                .playbackPath(req.getPlaybackPath())
                .playbackMode(req.getPlaybackMode() == null ? "direct" : req.getPlaybackMode())
                .build();
        // 逻辑删除后允许重新接入同一端点：恢复原行，既避免 uk_ip_port 冲突也沿用原 MediaMTX 路径编号。
        Camera deleted = cameraMapper.selectByEndpointIncludingDeleted(req.getIp(), port);
        if (deleted != null && Boolean.TRUE.equals(deleted.getDeleted())) {
            camera.setId(deleted.getId());
            cameraMapper.restoreDeleted(camera);
        } else {
            cameraMapper.insert(camera);
        }

        return bindMediaMtx(rtspUrl, camera);
    }

    private CameraVO bindMediaMtx(String rtspUrl, Camera camera) {
        // ONVIF 返回的 RTSP 不带账号密码，需嵌入后才能给 MediaMTX 拉流
        String url = ensureCredentials(normalizeRtspUrl(rtspUrl), camera.getUsername(), camera.getPassword());
        String pathName = mediaMtxService.ensurePath(
                camera.getId(), url, normalizeSourceProtocol(camera.getSourceProtocol()));
        camera.setMediamtxPath(pathName);
        camera.setWebrtcUrl(mediaMtxService.buildWebrtcUrl(pathName));
        camera.setHlsUrl(mediaMtxService.buildHlsUrl(pathName));
        camera.setLastError(null);
        cameraMapper.updateById(camera);
        clearStoredError(camera.getId());
        mediaGateway.invalidate();
        return applyLiveMediaState(camera, CameraVO.from(camera), mediaGateway.snapshot());
    }

    private String ensureCredentials(String rtspUrl, String username, String password) {
        if (rtspUrl == null || username == null || password == null) return rtspUrl;
        try {
            URI uri = new URI(rtspUrl);
            if (uri.getUserInfo() != null) return rtspUrl;
            if (uri.getHost() == null) throw new URISyntaxException(rtspUrl, "缺少主机名");
            return new URI(uri.getScheme(), username + ":" + password,
                    uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment()).toASCIIString();
        } catch (URISyntaxException e) {
            log.warn("RTSP地址格式错误: reason={}", e.getReason());
            throw new BusinessException("RTSP地址格式错误");
        }
    }

    /** 修复管理页面把 RTSP 查询参数中的 & 保存成 &amp; 的历史数据 */
    private String normalizeRtspUrl(String rtspUrl) {
        if (rtspUrl == null) return null;
        String normalized = rtspUrl.trim().replace("&amp;", "&");
        if (!normalized.startsWith("rtsp://") && !normalized.startsWith("rtsps://")) {
            throw new BusinessException("RTSP地址必须以 rtsp:// 或 rtsps:// 开头");
        }
        return normalized;
    }

    @Override
    public List<CameraVO> listCameras() {
        List<Camera> list = cameraMapper.selectList(
                new LambdaQueryWrapper<Camera>().orderByDesc(Camera::getCreatedAt));
        CameraMediaGateway.Snapshot snapshot = mediaGateway.snapshot();
        return list.stream()
                .map(camera -> applyLiveMediaState(camera, CameraVO.from(camera), snapshot))
                .toList();
    }

    @Override
    public CameraVO getCamera(Long id) {
        Camera camera = cameraMapper.selectById(id);
        if (camera == null) {
            throw new BusinessException("摄像头不存在");
        }
        // 播放地址按当前请求 Host 实时推导：库里存的是添加时写死的地址（可能是 localhost/旧 IP），
        // 直接给手机等外部设备会连不上；这里用 mediamtxPath + 请求 Host 重新生成。
        return applyLiveMediaState(camera, CameraVO.from(camera), mediaGateway.snapshot());
    }

    @Override
    @Transactional
    public CameraVO updateCamera(Long id, CameraRequest req) {
        Camera camera = cameraMapper.selectById(id);
        if (camera == null) {
            throw new BusinessException("摄像头不存在");
        }

        validatePlayback(req);

        CameraDiagnostics.Report previousDiagnostic = diagnostics.current(camera);
        String previousSourceHash = CameraMediaUtils.sourceFingerprint(camera);

        // 编辑时手动 RTSP 仍然优先；未填写且连接参数没有变化时保留原地址。
        String rtspUrl = req.getRtspUrl();
        boolean connectionChanged = req.getIp() != null || req.getPort() != null
                || req.getUsername() != null || req.getPassword() != null;
        if ((rtspUrl == null || rtspUrl.isBlank()) && connectionChanged) {
            rtspUrl = onvifService.getRtspUrl(
                    req.getIp() != null ? req.getIp() : camera.getIp(),
                    req.getPort() != null ? req.getPort() : camera.getPort(),
                    req.getUsername() != null ? req.getUsername() : camera.getUsername(),
                    req.getPassword() != null ? req.getPassword() : camera.getPassword());
        }
        if (rtspUrl == null || rtspUrl.isBlank()) rtspUrl = camera.getRtspUrl();
        if (rtspUrl == null || rtspUrl.isBlank()) {
            throw new BusinessException("ONVIF获取失败，请手动填写RTSP地址");
        }
        rtspUrl = normalizeRtspUrl(rtspUrl);

        if (req.getName() != null) camera.setName(req.getName());
        if (req.getDeviceCode() != null) camera.setDeviceCode(req.getDeviceCode());
        if (req.getDeviceModel() != null) camera.setDeviceModel(req.getDeviceModel());
        if (req.getIp() != null) camera.setIp(req.getIp());
        if (req.getPort() != null) camera.setPort(req.getPort());
        if (req.getUsername() != null) camera.setUsername(req.getUsername());
        if (req.getPassword() != null) camera.setPassword(req.getPassword());
        if (req.getBrand() != null) camera.setBrand(req.getBrand());
        if (req.getSourceProtocol() != null) {
            camera.setSourceProtocol(normalizeSourceProtocol(req.getSourceProtocol()));
        }
        if (req.getSnapshotUrl() != null) camera.setSnapshotUrl(req.getSnapshotUrl());
        if (req.getPlaybackMode() != null) camera.setPlaybackMode(req.getPlaybackMode());
        if (req.getPlaybackPath() != null) camera.setPlaybackPath(req.getPlaybackPath().trim());
        camera.setRtspUrl(rtspUrl);
        if (cameraMapper.selectCount(new LambdaQueryWrapper<Camera>()
                .eq(Camera::getIp, camera.getIp())
                .eq(Camera::getPort, camera.getPort())
                .ne(Camera::getId, id)) > 0) {
            throw new BusinessException("该IP和端口已添加");
        }
        if ("compatible".equals(camera.getPlaybackMode())) {
        String currentHash = CameraMediaUtils.sourceFingerprint(camera);
            boolean approved = currentHash.equals(camera.getCompatibilitySourceHash());
            boolean sampled = currentHash.equals(previousSourceHash) && previousDiagnostic != null
                    && previousDiagnostic.original() != null && previousDiagnostic.original().needsCompatibility();
            if (!approved && !sampled) throw new BusinessException("请先以原始输出保存并诊断此源，确认编码不兼容后再启用兼容输出");
            camera.setCompatibilitySourceHash(currentHash);
        }
        compatibility.stop(id);
        // 源参数可能已变：旧的兼容性结论按指纹自动失效，显式丢弃更直观。
        compatibility.forget(camera);
        diagnostics.invalidate(id);

        // 源地址可能已变：重新 ensurePath，让 MediaMTX 配置与库内记录保持一致。
        return bindMediaMtx(rtspUrl, camera);
    }

    @Override
    @Transactional
    public void deleteCamera(Long id) {
        Camera camera = cameraMapper.selectById(id);
        if (camera == null) {
            throw new BusinessException("摄像头不存在");
        }
        if (camera.getMediamtxPath() != null) {
            mediaMtxService.removePath(camera.getMediamtxPath());
        }
        // 兼容输出路径也要删：否则每删一次摄像头就在 MediaMTX 里留一条 cam_N_compatible 空壳。
            mediaMtxService.removePath(CameraCompatibilityUtils.compatiblePath(camera));
        compatibility.stop(id);
        compatibility.forget(camera);
        diagnostics.invalidate(id);
        cameraMapper.deleteById(id);
        mediaGateway.invalidate();
    }

    /** 后端启动时把数据库中已配置的摄像头路径补回 MediaMTX，避免服务重启后观看固定摄像头失败 */
    @PostConstruct
    public void syncMediaPaths() {
        List<Camera> cameras = cameraMapper.selectList(null);
        for (Camera camera : cameras) {
            try {
                String source = normalizeRtspUrl(camera.getRtspUrl());
                if (source == null || source.isBlank()) continue;
                String protocol = normalizeSourceProtocol(camera.getSourceProtocol());
                String pathName = mediaMtxService.ensurePath(
                        camera.getId(), ensureCredentials(source, camera.getUsername(), camera.getPassword()), protocol);
                camera.setMediamtxPath(pathName);
                camera.setRtspUrl(source);
                camera.setSourceProtocol(protocol);
                camera.setLastError(null);
                cameraMapper.updateById(camera);
                clearStoredError(camera.getId());
            } catch (Exception e) {
                log.warn("启动恢复摄像头 path 失败: id={}, path={}",
                        camera.getId(), camera.getMediamtxPath(), e);
            }
        }
    }

    /** 旧 refresh 接口保持可用，但变成只读状态刷新。 */
    @Override
    public CameraVO refreshStream(Long id) { return getCamera(id); }

    public CameraVO reconnectStream(Long id) {
        Camera camera = requireCamera(id);
        compatibility.stop(id);
        diagnostics.invalidate(id);
        String source = normalizeRtspUrl(camera.getRtspUrl());
        mediaMtxService.recreatePath(camera.getId(), ensureCredentials(source, camera.getUsername(), camera.getPassword()),
                normalizeSourceProtocol(camera.getSourceProtocol()));
        mediaGateway.invalidate();
        return getCamera(id);
    }

    public CameraVO preparePlayback(Long id) {
        Camera camera = requireCamera(id);
        long deadline = System.currentTimeMillis() + Math.max(0, mediaProperties.getPlayReadyTimeoutMs());
        if ("compatible".equals(camera.getPlaybackMode())) {
            compatibility.prepare(camera);
            return playbackResult(camera, CameraCompatibilityUtils.compatiblePath(camera),
                    "兼容输出正在启动（约需数秒），请稍候重试", deadline);
        }
        if ("existing".equals(camera.getPlaybackMode())) {
            // 管理员显式指定的输出路径：不做直连判定，等它就绪后如实回报。
            return playbackResult(camera, CameraMediaUtils.playbackPath(camera),
                    "所选播放路径尚未就绪，请检查该 MediaMTX 输出源", deadline);
        }
        // direct（默认）优先直连：WebRTC 支持则零额外延迟；不支持（如 H.265、PCM 44.1kHz）
        // 时按需启动兼容输出。源头暂不可达时不能退回直连地址，否则客户端拿 LPCM 直连协商
        // 会得到 400/404，所以先等源头就绪，必要时沿用上次的兼容性结论。
        CameraMediaGateway.PathState source = awaitSourceReady(camera, deadline);
        boolean needsTranscode = compatibility.remembered(camera);
        if (source != null) {
            needsTranscode = !WebrtcSupport.assess(source.trackInfos()).supported();
            if (needsTranscode) compatibility.remember(camera); else compatibility.forget(camera);
        }
        if (needsTranscode) {
            compatibility.prepareAuto(camera);
            return playbackResult(camera, CameraCompatibilityUtils.compatiblePath(camera),
                    "兼容输出正在启动（约需数秒），请稍候重试", deadline);
        }
        return playbackResult(camera, camera.getMediamtxPath(),
                "视频源未就绪，正在等待拉流；可点「重连源」或稍后重试", deadline);
    }

    /** 在总预算内等源头路径就绪；超时返回 null。不重建路径，避免客户端轮询期间反复拉起摄像头会话。 */
    private CameraMediaGateway.PathState awaitSourceReady(Camera camera, long deadline) {
        String path = camera.getMediamtxPath();
        if (path == null || path.isBlank()) return null;
        while (true) {
            mediaGateway.invalidate();
            CameraMediaGateway.Snapshot snapshot = mediaGateway.snapshot();
            CameraMediaGateway.PathState state = snapshot.paths().get(path);
            if (snapshot.available() && state != null && state.ready()) return state;
            if (System.currentTimeMillis() >= deadline) return null;
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
    }

    /** 等指定播放路径就绪（同一总预算内），再按 MediaMTX 的真实状态回报：就绪才 ready，绝不谎报。 */
    private CameraVO playbackResult(Camera camera, String playbackPath, String pendingMessage, long deadline) {
        if (playbackPath != null && !playbackPath.isBlank()) {
            while (true) {
                mediaGateway.invalidate();
                CameraMediaGateway.Snapshot snapshot = mediaGateway.snapshot();
                CameraMediaGateway.PathState state = snapshot.paths().get(playbackPath);
                if (snapshot.available() && state != null && state.ready()) break;
                if (System.currentTimeMillis() >= deadline) break;
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        CameraVO vo = getCamera(camera.getId());
        if (playbackPath != null && !playbackPath.isBlank()) {
            vo.setPlaybackPath(playbackPath);
            vo.setWebrtcUrl(mediaMtxService.buildWebrtcUrl(playbackPath));
            vo.setHlsUrl(mediaMtxService.buildHlsUrl(playbackPath));
        }
        CameraMediaGateway.Snapshot fresh = mediaGateway.snapshot();
        CameraMediaGateway.PathState state = playbackPath == null ? null : fresh.paths().get(playbackPath);
        boolean ready = fresh.available() && state != null && state.ready();
        vo.setPlaybackSourceState(!fresh.available() ? "unknown" : ready ? "ready" : "not_ready");
        if (state != null) vo.setTracks(state.tracks());
        vo.setSourceMessage(ready ? "播放路径已就绪" : pendingMessage);
        return vo;
    }

    private Camera requireCamera(Long id) {
        Camera camera = cameraMapper.selectById(id);
        if (camera == null) throw new BusinessException("摄像头不存在");
        return camera;
    }

    private void clearStoredError(Long id) {
        // updateById 的 NOT_NULL 策略不会写入 null；明确清除此字段，避免旧提示残留。
        cameraMapper.update(null, new UpdateWrapper<Camera>().eq("id", id).isNotNull("last_error").set("last_error", null));
    }

    private CameraVO applyLiveMediaState(Camera camera, CameraVO vo, CameraMediaGateway.Snapshot snapshot) {
        vo.setLastError(null);
        vo.setCheckedAt(snapshot.checkedAt());
        vo.setDiagnostic(diagnostics.current(camera));
        if (vo.getDiagnostic() != null && vo.getDiagnostic().forwarded() != null) {
            vo.getDiagnostic().forwarded().tracks().forEach(track -> {
                if ("video".equals(track.type())) vo.setVideoCodec(track.codec());
                if ("audio".equals(track.type())) vo.setAudioCodec(track.codec());
            });
        }
        String path = camera.getMediamtxPath();
        String playPath = CameraMediaUtils.playbackPath(camera);
        vo.setPlaybackPath(playPath);
        vo.setTracks(java.util.List.of());
        if (playPath != null && !playPath.isBlank()) {
            vo.setWebrtcUrl(mediaMtxService.buildWebrtcUrl(playPath));
            vo.setHlsUrl(mediaMtxService.buildHlsUrl(playPath));
        }
        CameraMediaGateway.PathState source = path == null ? null : snapshot.paths().get(path);
        CameraMediaGateway.PathState playback = playPath == null ? null : snapshot.paths().get(playPath);
        vo.setPlaybackSourceState(!snapshot.available() ? "unknown" : playback != null && playback.ready() ? "ready" : "not_ready");
        if (playback != null) vo.setTracks(playback.tracks());
        if (path == null || path.isBlank()) {
            vo.setStatus(-1); vo.setSourceState("unconfigured"); vo.setSourceMessage("尚未配置视频源");
        } else if (!snapshot.available()) {
            vo.setStatus(-1); vo.setSourceState("unknown"); vo.setSourceMessage("状态未知：MediaMTX 管理接口不可用");
        } else if (source != null && source.ready()) {
            vo.setStatus(1); vo.setSourceState("ready"); vo.setSourceMessage("视频源在线");
            // 源头已恢复则清掉旧错误，避免认证失败等提示一直残留在列表里。
            if (camera.getLastError() != null) clearStoredError(camera.getId());
        } else {
            vo.setStatus(0); vo.setSourceState("not_ready"); vo.setSourceMessage("视频源未就绪 · 等待拉流或执行诊断");
            CameraDiagnostics.Report report = vo.getDiagnostic();
            // 只有诊断明确为 AUTH 时才提示认证失败，其余未就绪仍按等待拉流处理。
            if (report != null && report.original() != null && "AUTH".equals(report.original().code())) {
                vo.setLastError(report.original().message());
                vo.setSourceMessage("接入失败 · RTSP 认证失败或无权限");
            }
        }
        return vo;
    }

    private void validatePlayback(CameraRequest req) {
        if (req.getPlaybackMode() != null && !java.util.Set.of("direct", "existing", "compatible").contains(req.getPlaybackMode()))
            throw new BusinessException("播放模式必须为 direct、existing 或 compatible");
        if (req.getPlaybackPath() != null && !req.getPlaybackPath().isBlank()) CameraMediaGateway.validPath(req.getPlaybackPath().trim());
        if ("existing".equals(req.getPlaybackMode()) && (req.getPlaybackPath() == null || req.getPlaybackPath().isBlank()))
            throw new BusinessException("请选择已配置的 MediaMTX 输出路径");
    }

    private String normalizeSourceProtocol(String sourceProtocol) {
        if (sourceProtocol == null) return "automatic";
        String value = sourceProtocol.trim().toLowerCase();
        return switch (value) {
            case "tcp", "udp" -> value;
            default -> "automatic";
        };
    }

    private void validateNewCamera(CameraRequest req) {
        if (req.getName() == null || req.getName().isBlank()) throw new BusinessException("摄像头名称不能为空");
        if (req.getIp() == null || req.getIp().isBlank()) throw new BusinessException("IP地址不能为空");
        if (req.getUsername() == null || req.getUsername().isBlank()) throw new BusinessException("账号不能为空");
        if (req.getPassword() == null || req.getPassword().isBlank()) throw new BusinessException("密码不能为空");
    }
}
