package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.dto.LocationUpdateRequest;
import hnist.communication.dto.TrajectorySessionFinishRequest;
import hnist.communication.dto.TrajectorySessionStartRequest;
import hnist.communication.entity.LocationHistory;
import hnist.communication.entity.TrajectorySession;
import hnist.communication.service.AccessControlService;
import hnist.communication.service.LocationService;
import hnist.communication.vo.LocationVO;
import hnist.communication.websocket.LocationWebSocketHandler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/** 位置上报、轨迹会话与在线状态查询。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;
    private final LocationWebSocketHandler locationWsHandler;
    private final AccessControlService accessControl;

    @PostMapping("/location/update")
    public Result<Void> update(@Valid @RequestBody LocationUpdateRequest req, Authentication authentication) {
        locationService.updateLocationWithSession((Long) authentication.getPrincipal(), req.getLatitude(), req.getLongitude(),
                req.getAddress(), req.getSpeed(), req.getDirection(), req.getAccuracy(), req.getSessionId(),
                req.getTimestamp());
        return Result.success();
    }

    @PostMapping("/trajectory/session/start")
    public Result<TrajectorySession> startSession(@RequestBody TrajectorySessionStartRequest req, Authentication authentication) {
        return Result.success(locationService.startSession(
                (Long) authentication.getPrincipal(), req.getSessionId(), req.getStartTime()));
    }

    @PostMapping("/trajectory/session/finish")
    public Result<TrajectorySession> finishSession(@RequestBody TrajectorySessionFinishRequest req,
                                                    Authentication authentication) {
        requireSessionOwnerOrAdmin(req.getSessionId(), authentication);
        return Result.success(locationService.finishSession(req.getSessionId(), req.getEndTime(), req.getDurationSec()));
    }

    @GetMapping("/trajectory/sessions")
    public Result<List<TrajectorySession>> listSessions(@RequestParam Long userId,
                                                        Authentication authentication) {
        accessControl.requireSelfOrAdmin(userId, authentication, "无权查看其他用户的轨迹会话");
        return Result.success(locationService.listSessions(userId));
    }

    @GetMapping("/trajectory/history/{sessionId}")
    public Result<List<LocationHistory>> getHistoryBySession(@PathVariable String sessionId,
                                                             Authentication authentication) {
        requireSessionOwnerOrAdmin(sessionId, authentication);
        return Result.success(locationService.getHistoryBySession(sessionId));
    }

    @DeleteMapping("/trajectory/session/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId, Authentication authentication) {
        requireSessionOwnerOrAdmin(sessionId, authentication);
        locationService.deleteSession(sessionId);
        return Result.success();
    }

    @GetMapping("/location/user/{userId}")
    public Result<LocationVO> getUserLocation(@PathVariable Long userId, Authentication authentication) {
        accessControl.requireSameGroupOrAdmin(userId, authentication, "无权查看其他用户的位置");
        return Result.success(locationService.getUserLocation(userId));
    }

    @GetMapping("/location/group/{groupId}")
    public Result<List<LocationVO>> getGroupLocations(@PathVariable Long groupId, Authentication authentication) {
        accessControl.requireGroupMemberOrAdmin(groupId, authentication, "无权查看该群组的位置");
        return Result.success(locationService.getGroupLocations(groupId));
    }

    @GetMapping("/locations/history/{userId}")
    public Result<List<LocationHistory>> getHistory(@PathVariable Long userId,
                                                     @RequestParam String date,
                                                     Authentication authentication) {
        accessControl.requireSelfOrAdmin(userId, authentication, "无权查看其他用户的历史轨迹");
        return Result.success(locationService.getHistory(userId, date));
    }

    @GetMapping("/locations/history/{userId}/dates")
    public Result<List<String>> getAvailableDates(@PathVariable Long userId,
                                                   @RequestParam(defaultValue = "7") int days,
                                                   Authentication authentication) {
        accessControl.requireSelfOrAdmin(userId, authentication, "无权查看其他用户的历史轨迹");
        return Result.success(locationService.getAvailableDates(userId, days));
    }

    @GetMapping("/location/online/{groupId}")
    public Result<Set<Long>> getOnlineUsers(@PathVariable Long groupId, Authentication authentication) {
        accessControl.requireGroupMemberOrAdmin(groupId, authentication, "无权查看该群组的在线状态");
        return Result.success(locationWsHandler.getOnlineUsers(groupId));
    }

    /** 全系统在线用户 ID 集合，通讯录人员与后台手机摄像头共用同一在线口径。 */
    @GetMapping("/location/online")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Set<Long>> getAllOnlineUsers() {
        return Result.success(locationWsHandler.getOnlineUserIds());
    }

    private void requireSessionOwnerOrAdmin(String sessionId, Authentication authentication) {
        TrajectorySession session = locationService.getSession(sessionId);
        accessControl.requireSelfOrAdmin(session.getUserId(), authentication, "无权操作其他用户的轨迹");
    }
}
