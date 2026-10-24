package hnist.communication.controller;

import hnist.communication.exception.BusinessException;
import hnist.communication.common.Result;
import hnist.communication.entity.PhoneCamera;
import hnist.communication.service.AccessControlService;
import hnist.communication.service.PhoneCameraService;
import hnist.communication.service.UserService;
import hnist.communication.vo.PhoneCameraVO;
import hnist.communication.vo.UserVO;
import hnist.communication.websocket.PhoneCamWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 手机摄像头推流请求、查看状态与心跳管理。 */
@Slf4j
@RestController
@RequestMapping("/api/phone-cameras")
@RequiredArgsConstructor
public class PhoneCameraController {

    private final PhoneCameraService phoneCameraService;
    private final PhoneCamWebSocketHandler wsHandler;
    private final UserService userService;
    private final AccessControlService accessControl;

    @GetMapping
    public Result<List<PhoneCameraVO>> list(Authentication authentication) {
        List<PhoneCameraVO> all = phoneCameraService.listAll();
        if (accessControl.hasAdminRole(authentication)) {
            return Result.success(all);
        }
        Long currentUserId = accessControl.currentUserId(authentication);
        var visibleUserIds = accessControl.sameGroupUserIds(currentUserId);
        return Result.success(all.stream()
                .filter(camera -> visibleUserIds.contains(camera.getUserId()))
                .toList());
    }

    /** 请求手机推流；设备离线或信令断开时返回具体业务原因，成功后下发指令并登记查看者。 */
    @PostMapping("/{id}/view")
    public Result<Void> requestView(@PathVariable Long id, Authentication authentication) {
        // 先确认设备在线且信令通道已连接，避免指令发空、状态误标为"推流中"。
        // 用 BusinessException 透出具体原因，否则会被全局异常处理器吞成"服务器内部错误"。
        PhoneCameraVO target = phoneCameraService.listAll().stream()
                .filter(v -> v.getId().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException("设备不存在"));
        accessControl.requireSameGroupOrAdmin(
                target.getUserId(), authentication, "无权查看其他群组的手机摄像头");
        // 在线状态与通讯录人员同步：所属用户离线时摄像头按离线处理，不允许请求推流。
        if (target.getStatus() == null || target.getStatus() == PhoneCamera.STATUS_OFFLINE) {
            throw new BusinessException("设备当前离线，请让手机重新打开App并登录后再试");
        }
        if (!wsHandler.isDeviceConnected(target.getDeviceId())) {
            throw new BusinessException("设备连接已断开，请让手机重新打开App并登录后再试");
        }
        // 记录请求者身份透传给手机端（"谁请求推流就显示谁"）；昵称优先，无昵称回退 JWT 用户名，
        // 与通讯录"昵称||用户名"的显示口径一致。
        Long requesterId = accessControl.currentUserId(authentication);
        String requesterName = null;
        requesterName = authentication.getDetails() instanceof String s ? s : null;
        try {
            UserVO requester = userService.getUser(requesterId);
            if (requester.getNickname() != null && !requester.getNickname().isBlank()) {
                requesterName = requester.getNickname();
            } else if (requester.getUsername() != null && !requester.getUsername().isBlank()) {
                requesterName = requester.getUsername();
            }
        } catch (BusinessException e) {
            log.warn("查询查看者资料失败，回退 JWT 用户名: userId={}", requesterId, e);
        }
        Map<String, Object> msg = new HashMap<>(phoneCameraService.prepareStream(id));
        msg.put("requestedBy", requesterName == null || requesterName.isBlank() ? "管理员" : requesterName);
        msg.put("requestedById", requesterId);
        wsHandler.sendToDevice(target.getDeviceId(), msg);
        // 登记查看者；播放页心跳超时后自动停流，实现"请求者退出 → 推流结束"。
        phoneCameraService.registerViewer(id, requesterId, requesterName);
        return Result.success();
    }

    /** 查看者心跳（播放页每 10s 调用；超时视为已退出，自动停止推流） */
    @PostMapping("/{id}/viewing")
    public Result<Void> viewing(@PathVariable Long id, Authentication authentication) {
        requireCameraAccess(id, authentication);
        phoneCameraService.touchViewer(id);
        return Result.success();
    }

    /** 查询流是否已到达MediaMTX（后台轮询播放就绪状态用） */
    @GetMapping("/{id}/ready")
    public Result<Boolean> streamReady(@PathVariable Long id, Authentication authentication) {
        requireCameraAccess(id, authentication);
        return Result.success(phoneCameraService.isStreamReady(id));
    }

    /** 查看状态聚合：state 为 streaming/rejected/waiting，查看方据此感知对方拒绝并退出等待页。 */
    @GetMapping("/{id}/view-status")
    public Result<Map<String, Object>> viewStatus(@PathVariable Long id, Authentication authentication) {
        requireCameraAccess(id, authentication);
        return Result.success(phoneCameraService.getViewStatus(id));
    }

    @PostMapping("/{id}/stop")
    public Result<Void> stop(@PathVariable Long id, Authentication authentication) {
        requireCameraAccess(id, authentication);
        String deviceId = phoneCameraService.stopStream(id);
        phoneCameraService.clearViewer(id);
        if (deviceId != null) {
            // 透传停止人昵称（超时自动停流时无操作者，手机端显示"查看已结束"）。
            Map<String, Object> msg = new HashMap<>();
            Long operatorId = accessControl.currentUserId(authentication);
            if (operatorId != null) {
                String name = authentication.getDetails() instanceof String s ? s : null;
                try {
                    UserVO stopper = userService.getUser(operatorId);
                    if (stopper.getNickname() != null && !stopper.getNickname().isBlank()) {
                        name = stopper.getNickname();
                    } else if (stopper.getUsername() != null && !stopper.getUsername().isBlank()) {
                        name = stopper.getUsername();
                    }
                } catch (BusinessException e) {
                    log.warn("查询停止人资料失败，回退 JWT 用户名: userId={}", operatorId, e);
                }
                if (name != null && !name.isBlank()) {
                    msg.put("stoppedBy", name);
                }
            }
            msg.put("type", "stop_stream");
            wsHandler.sendToDevice(deviceId, msg);
        }
        return Result.success();
    }

    private void requireCameraAccess(Long id, Authentication authentication) {
        PhoneCameraVO target = phoneCameraService.listAll().stream()
                .filter(camera -> camera.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new BusinessException("设备不存在"));
        accessControl.requireSameGroupOrAdmin(
                target.getUserId(), authentication, "无权查看其他群组的手机摄像头");
    }
}
