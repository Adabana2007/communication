package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.PhoneCamera;
import hnist.communication.entity.User;
import hnist.communication.mapper.PhoneCameraMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.MediaMtxService;
import hnist.communication.service.PhoneCameraService;
import hnist.communication.vo.PhoneCameraVO;
import hnist.communication.websocket.LocationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 手机摄像头共享：设备注册/心跳、推流信令及查看者超时停止。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneCameraServiceImpl implements PhoneCameraService {

    private final PhoneCameraMapper phoneCameraMapper;
    private final UserMapper userMapper;
    private final MediaMtxService mediaMtxService;
    private final LocationWebSocketHandler locationWsHandler;

    /** 查看者注册表：cameraId → 请求查看者（含最后心跳），用于"请求者退出 → 自动结束推流" */
    private final ConcurrentHashMap<Long, ViewerInfo> viewers = new ConcurrentHashMap<>();

    /** 拒绝标记：cameraId → 标记时间；查看方轮询 /view-status 感知后退出等待，新一次查看请求会清除。 */
    private final ConcurrentHashMap<Long, Long> rejectedFlags = new ConcurrentHashMap<>();
    /** 最近一次推流失败原因（cameraId → 原因），随 /view-status 透传给查看方。 */
    private final ConcurrentHashMap<Long, String> streamErrors = new ConcurrentHashMap<>();
    /** 本轮查看已显式停止的 cameraId，防止迟到的 confirmStreamReady 把状态改回推流中。 */
    private final Set<Long> stoppedStreams = ConcurrentHashMap.newKeySet();

    /** 查看者心跳超时（毫秒）：超时视为请求者已退出查看页面，自动停止推流 */
    private static final long VIEWER_STALE_MS = 60_000;

    private static class ViewerInfo {
        final Long userId;
        final String username;
        volatile long lastSeen;

        ViewerInfo(Long userId, String username, long lastSeen) {
            this.userId = userId;
            this.username = username;
            this.lastSeen = lastSeen;
        }
    }

    @Override
    @Transactional
    public void registerDevice(String deviceId, Long userId, String deviceName) {
        PhoneCamera pc = phoneCameraMapper.selectOne(
                new LambdaQueryWrapper<PhoneCamera>().eq(PhoneCamera::getDeviceId, deviceId));
        if (pc == null) {
            PhoneCamera deleted = phoneCameraMapper.selectByDeviceIdIncludingDeleted(deviceId);
            if (deleted != null) {
                deleted.setUserId(userId);
                deleted.setDeviceName(deviceName);
                deleted.setStatus(PhoneCamera.STATUS_ONLINE);
                deleted.setHlsUrl(null);
                deleted.setMediamtxPath(null);
                deleted.setLastHeartbeat(LocalDateTime.now());
                phoneCameraMapper.restoreDeleted(deleted);
                pc = deleted;
            } else {
                pc = PhoneCamera.builder()
                        .deviceId(deviceId).userId(userId).deviceName(deviceName)
                        .status(PhoneCamera.STATUS_ONLINE).lastHeartbeat(LocalDateTime.now()).build();
                phoneCameraMapper.insert(pc);
            }
        } else {
            // 同一设备换号登录时复用记录，改为新用户并复位为在线。
            pc.setUserId(userId);
            pc.setDeviceName(deviceName);
            pc.setStatus(PhoneCamera.STATUS_ONLINE);
            pc.setLastHeartbeat(LocalDateTime.now());
            phoneCameraMapper.updateById(pc);
        }
    }

    @Override
    @Transactional
    public void updateHeartbeat(String deviceId) {
        PhoneCamera pc = phoneCameraMapper.selectOne(
                new LambdaQueryWrapper<PhoneCamera>().eq(PhoneCamera::getDeviceId, deviceId));
        if (pc != null) {
            pc.setLastHeartbeat(LocalDateTime.now());
            phoneCameraMapper.updateById(pc);
        }
    }

    @Override
    @Transactional
    public void markOffline(String deviceId) {
        PhoneCamera pc = phoneCameraMapper.selectOne(
                new LambdaQueryWrapper<PhoneCamera>().eq(PhoneCamera::getDeviceId, deviceId));
        if (pc != null) {
            pc.setStatus(PhoneCamera.STATUS_OFFLINE);
            // updateById 默认忽略 null；必须写空串才能真正清掉上一轮播放地址。
            pc.setHlsUrl("");
            pc.setMediamtxPath("");
            phoneCameraMapper.updateById(pc);
            viewers.remove(pc.getId());
            stoppedStreams.add(pc.getId());
        }
    }

    @Override
    public List<PhoneCameraVO> listAll() {
        List<PhoneCamera> list = phoneCameraMapper.selectList(
                new LambdaQueryWrapper<PhoneCamera>().orderByDesc(PhoneCamera::getCreatedAt));
        // 批量查用户名，避免N+1
        List<Long> userIds = list.stream().map(PhoneCamera::getUserId).distinct().toList();
        Map<Long, String> nameMap = userIds.isEmpty() ? Map.of()
                : userMapper.selectByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getUserId, User::getUsername, (left, right) -> left));
        // 展示状态与通讯录在线集合对齐：所属用户离线时摄像头必然离线，仅在展示层修正，不回写数据库。
        Set<Long> onlineUserIds = locationWsHandler.getOnlineUserIds();
        // App 被杀时 TCP 可能半开，服务端收不到断开事件；90 秒无心跳的一律按离线展示，
        // 否则状态会永远停在"在线/推流中"。
        LocalDateTime staleBefore = LocalDateTime.now().minusSeconds(90);
        return list.stream().map(pc -> {
            int status = pc.getStatus() == null ? PhoneCamera.STATUS_OFFLINE : pc.getStatus();
            if (!onlineUserIds.contains(pc.getUserId())) {
                status = PhoneCamera.STATUS_OFFLINE;
            } else if (status != PhoneCamera.STATUS_OFFLINE
                    && pc.getLastHeartbeat() != null && pc.getLastHeartbeat().isBefore(staleBefore)) {
                status = PhoneCamera.STATUS_OFFLINE;
            }
            PhoneCameraVO vo = PhoneCameraVO.from(pc, nameMap.get(pc.getUserId()), status);
            // 推流中/已有媒体路径 → 附带 WebRTC 播放地址（WHEP 拉流，降延时）
            if (pc.getMediamtxPath() != null && !pc.getMediamtxPath().isBlank()) {
                vo.setWebrtcUrl(mediaMtxService.buildWebrtcUrl(pc.getMediamtxPath()));
            }
            return vo;
        }).toList();
    }

    @Override
    @Transactional
    public Map<String, Object> prepareStream(Long id) {
        PhoneCamera pc = phoneCameraMapper.selectById(id);
        // 用 BusinessException 透传真实原因，避免被统一处理成"服务器内部错误"。
        if (pc == null) throw new BusinessException("设备不存在");
        if (pc.getStatus() == PhoneCamera.STATUS_OFFLINE) throw new BusinessException("设备当前离线");

        // MediaMTX 收到 RTMP 推流会自动创建 path，无需提前 addPath。
        String pathName = "phone_" + pc.getDeviceId().replace("-", "");
        String rtmpUrl = mediaMtxService.buildRtmpPushUrl(pathName);
        // 新客户端走 WHIP 以降低延迟；rtmpUrl 仍保留给老版本，并作为流路径载体。
        String whipUrl = mediaMtxService.buildWhipPushUrl(pathName);
        String hlsUrl = mediaMtxService.buildHlsUrl(pathName);

        pc.setMediamtxPath(pathName);
        pc.setHlsUrl(hlsUrl);
        pc.setStatus(PhoneCamera.STATUS_STREAMING);
        phoneCameraMapper.updateById(pc);
        // 新一次查看请求清除上一次的拒绝标记，防止旧拒绝误伤新请求。
        rejectedFlags.remove(id);
        streamErrors.remove(id);
        stoppedStreams.remove(id);

        return Map.of("type", "start_stream", "rtmpUrl", rtmpUrl, "whipUrl", whipUrl);
    }

    @Override
    @Transactional
    public void confirmStreamReady(String deviceId) {
        PhoneCamera pc = phoneCameraMapper.selectOne(
                new LambdaQueryWrapper<PhoneCamera>().eq(PhoneCamera::getDeviceId, deviceId));
        // 只在本轮未停止、未被拒绝且已有 path 时确认，避免迟到的确认覆盖停止/拒绝状态。
        if (pc != null && !stoppedStreams.contains(pc.getId()) && !rejectedFlags.containsKey(pc.getId())
                && pc.getMediamtxPath() != null && !pc.getMediamtxPath().isBlank()) {
            pc.setStatus(PhoneCamera.STATUS_STREAMING);
            phoneCameraMapper.updateById(pc);
        }
    }

    @Override
    @Transactional
    public void reportStreamError(String deviceId, String message) {
        PhoneCamera pc = phoneCameraMapper.selectOne(
                new LambdaQueryWrapper<PhoneCamera>().eq(PhoneCamera::getDeviceId, deviceId));
        if (pc != null) {
            pc.setStatus(PhoneCamera.STATUS_ONLINE);
            pc.setHlsUrl("");
            pc.setMediamtxPath("");
            phoneCameraMapper.updateById(pc);
            // 推流失败意味着本次查看无法进行，写拒绝标记让查看方退出等待。
            rejectedFlags.put(pc.getId(), System.currentTimeMillis());
            viewers.remove(pc.getId());
            // 失败原因同时写日志并随 /view-status 透传，便于现场区分不可达、超时或 HTTP 拒绝。
            String reason = message == null ? "" : message.trim();
            if (!reason.isEmpty()) {
                streamErrors.put(pc.getId(), reason);
            }
            log.warn("手机推流失败 deviceId={} cameraId={} 原因={}", deviceId, pc.getId(),
                    reason.isEmpty() ? "(客户端未上报原因)" : reason);
        }
    }

    @Override
    @Transactional
    public void rejectStreamByDevice(String deviceId) {
        // 被查看方拒绝（授权弹窗点拒绝/摄像头权限未授权）：复位为在线未推流，
        // 并写拒绝标记供查看方轮询感知。
        PhoneCamera pc = phoneCameraMapper.selectOne(
                new LambdaQueryWrapper<PhoneCamera>().eq(PhoneCamera::getDeviceId, deviceId));
        if (pc == null) {
            return;
        }
        pc.setStatus(PhoneCamera.STATUS_ONLINE);
        pc.setHlsUrl("");
        pc.setMediamtxPath("");
        phoneCameraMapper.updateById(pc);
        rejectedFlags.put(pc.getId(), System.currentTimeMillis());
        viewers.remove(pc.getId());
        log.info("设备 {} 拒绝了摄像头查看请求, cameraId={}", deviceId, pc.getId());
    }

    @Override
    public Map<String, Object> getViewStatus(Long id) {
        PhoneCamera pc = phoneCameraMapper.selectById(id);
        boolean offline = pc == null || pc.getStatus() == null || pc.getStatus() == PhoneCamera.STATUS_OFFLINE;
        boolean stopped = stoppedStreams.contains(id);
        boolean rejected = rejectedFlags.containsKey(id);
        boolean ready = !offline && !stopped && !rejected && isStreamReady(id);
        // 状态优先级：offline/stopped/rejected 高于就绪判断，避免已结束的会话仍显示 streaming。
        String state;
        if (offline) {
            state = "offline";
        } else if (stopped) {
            state = "stopped";
        } else if (rejected) {
            state = "rejected";
        } else if (ready) {
            state = "streaming";
        } else {
            state = "waiting";
        }
        // 带上推流失败原因（没有则空串），查看方可直接显示具体原因。
        String error = streamErrors.getOrDefault(id, "");
        return Map.of("state", state, "ready", ready, "error", error);
    }

    @Override
    @Transactional
    public String stopStream(Long id) {
        PhoneCamera pc = phoneCameraMapper.selectById(id);
        if (pc == null) return null;
        pc.setStatus(PhoneCamera.STATUS_ONLINE);
        pc.setHlsUrl("");
        pc.setMediamtxPath("");
        phoneCameraMapper.updateById(pc);
        // 显式停止即本轮查看结束，拒绝标记一并复位，避免误伤下一次请求。
        rejectedFlags.remove(id);
        streamErrors.remove(id);
        viewers.remove(id);
        stoppedStreams.add(id);
        return pc.getDeviceId();
    }

    @Override
    @Transactional
    public void stopStreamByDevice(String deviceId) {
        PhoneCamera pc = phoneCameraMapper.selectOne(
                new LambdaQueryWrapper<PhoneCamera>().eq(PhoneCamera::getDeviceId, deviceId));
        if (pc != null) stopStream(pc.getId());
    }

    // ------------------------------------------------------------
    // 查看者心跳（请求者退出 → 自动结束推流）
    // ------------------------------------------------------------

    @Override
    public void registerViewer(Long cameraId, Long userId, String username) {
        if (cameraId == null) return;
        viewers.put(cameraId, new ViewerInfo(userId, username, System.currentTimeMillis()));
    }

    @Override
    public void touchViewer(Long cameraId) {
        ViewerInfo v = viewers.get(cameraId);
        if (v != null) {
            v.lastSeen = System.currentTimeMillis();
        }
    }

    @Override
    public void clearViewer(Long cameraId) {
        viewers.remove(cameraId);
    }

    /**
     * 检查查看者心跳：超时（60s 无心跳 = 请求者已退出查看页面）→ 停止推流。
     * 返回被停止的设备 deviceId 列表（供调用方向手机下发 stop_stream）。
     */
    @Override
    @Transactional
    public List<String> stopStaleStreams() {
        long threshold = System.currentTimeMillis() - VIEWER_STALE_MS;
        List<Long> stale = viewers.entrySet().stream()
                .filter(e -> e.getValue().lastSeen < threshold)
                .map(Map.Entry::getKey)
                .toList();
        List<String> stopped = new ArrayList<>();
        for (Long id : stale) {
            viewers.remove(id);
            String deviceId = stopStream(id);
            if (deviceId != null) {
                stopped.add(deviceId);
            }
        }
        return stopped;
    }

    @Override
    public boolean isStreamReady(Long id) {
        PhoneCamera pc = phoneCameraMapper.selectById(id);
        // 设备不在推流状态或本轮已停止时，即使 MediaMTX 仍有旧流也判定为未就绪。
        if (pc == null || pc.getStatus() == null || pc.getStatus() != PhoneCamera.STATUS_STREAMING
                || stoppedStreams.contains(id) || pc.getMediamtxPath() == null || pc.getMediamtxPath().isEmpty()) {
            return false;
        }
        return mediaMtxService.isSourceReady(pc.getMediamtxPath());
    }
}
