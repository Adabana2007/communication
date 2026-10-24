package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.GroupMember;
import hnist.communication.entity.LocationHistory;
import hnist.communication.entity.TrajectorySession;
import hnist.communication.entity.User;
import hnist.communication.entity.UserLocation;
import hnist.communication.mapper.GroupMemberMapper;
import hnist.communication.mapper.LocationHistoryMapper;
import hnist.communication.mapper.TrajectorySessionMapper;
import hnist.communication.mapper.UserLocationMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.LocationService;
import hnist.communication.vo.LocationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 实时位置与轨迹会话：维护最新坐标、轨迹点去重及会话里程统计。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationServiceImpl implements LocationService {

    /** 轨迹时间统一按东八区存储（与客户端"点击记录"的本地时刻一致），
     *  避免服务器时区/时钟与手机不一致导致历史轨迹时间偏移。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final UserLocationMapper locationMapper;
    private final LocationHistoryMapper historyMapper;
    private final TrajectorySessionMapper sessionMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public void updateLocation(Long userId, Double latitude, Double longitude, String address,
                               Double speed, Double direction, Double accuracy) {
        updateLocationWithSession(userId, latitude, longitude, address, speed, direction, accuracy, null, null);
    }

    @Override
    @Transactional
    public void updateLocationWithSession(Long userId, Double latitude, Double longitude, String address,
                                          Double speed, Double direction, Double accuracy, String sessionId,
                                          Long timestamp) {
        UserLocation existing = locationMapper.selectOne(
                new LambdaQueryWrapper<UserLocation>().eq(UserLocation::getUserId, userId));
        if (existing != null) {
            existing.setLatitude(latitude);
            existing.setLongitude(longitude);
            existing.setAddress(address);
            locationMapper.updateById(existing);
        } else {
            UserLocation loc = UserLocation.builder()
                    .userId(userId).latitude(latitude)
                    .longitude(longitude).address(address).build();
            UserLocation deleted = locationMapper.selectByUserIdIncludingDeleted(userId);
            if (deleted != null) {
                loc.setId(deleted.getId());
                locationMapper.restoreDeleted(loc);
            } else {
                locationMapper.insert(loc);
            }
        }

        // 写入历史轨迹（轨迹点由采集器 30 秒批量补传，优先用客户端采集时间，
        // 否则 recordedAt 全挤在上传时刻 → 回放时长被压缩）
        LocalDateTime recordedAt = (timestamp != null && timestamp > 0)
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZONE)
                : LocalDateTime.now(ZONE);
        if (sessionId != null && !sessionId.isBlank() && timestamp != null && timestamp > 0)
            recordedAt = recordedAt.withNano(0); // 与现有 DATETIME(0) 列精度一致。
        // 补传响应丢失时，同一会话/采集时刻/坐标的点不能被重复计入里程。
        if (sessionId != null && !sessionId.isBlank() && timestamp != null && timestamp > 0
                && historyMapper.selectCount(new LambdaQueryWrapper<LocationHistory>()
                    .eq(LocationHistory::getUserId, userId).eq(LocationHistory::getSessionId, sessionId)
                    .eq(LocationHistory::getRecordedAt, recordedAt.withNano(0))
                    .eq(LocationHistory::getLatitude, latitude).eq(LocationHistory::getLongitude, longitude)) > 0) return;
        LocationHistory history = LocationHistory.builder()
                .userId(userId).latitude(latitude).longitude(longitude)
                .speed(speed).direction(direction).accuracy(accuracy)
                .recordedAt(recordedAt).sessionId(sessionId).build();
        historyMapper.insert(history);
    }

    @Override
    @Transactional
    public TrajectorySession startSession(Long userId, String sessionId, Long startTime) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{8,64}"))
            throw new BusinessException("轨迹会话编号无效");
        TrajectorySession existing = sessionMapper.selectOne(
                new LambdaQueryWrapper<TrajectorySession>().eq(TrajectorySession::getSessionId, sessionId));
        if (existing != null) {
            if (!userId.equals(existing.getUserId())) throw new BusinessException("轨迹会话编号已被占用");
            return existing;
        }
        // 开始时间优先用客户端"点击记录"的时刻，保证轨迹时间与用户操作一致。
        LocalDateTime start = (startTime != null && startTime > 0)
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(startTime), ZONE)
                : LocalDateTime.now(ZONE);
        TrajectorySession session = TrajectorySession.builder()
                .sessionId(sessionId)
                .userId(userId)
                .startTime(start)
                .status(0)
                .distanceKm(0.0)
                .durationSec(0)
                .pointCount(0)
                .createdAt(LocalDateTime.now(ZONE))
                .build();
        TrajectorySession deleted = sessionMapper.selectBySessionIdIncludingDeleted(sessionId);
        if (deleted != null) {
            if (!userId.equals(deleted.getUserId())) throw new BusinessException("轨迹会话编号已被占用");
            session.setId(deleted.getId());
            sessionMapper.restoreDeleted(session);
            log.info("[Trajectory] 恢复轨迹会话: sessionId={}, userId={}", sessionId, userId);
            return session;
        }
        try { sessionMapper.insert(session); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 唯一键冲突说明并发重复开始：查回已先写入的会话，仅同一用户才视为幂等成功。
            TrajectorySession winner = sessionMapper.selectOne(
                    new LambdaQueryWrapper<TrajectorySession>().eq(TrajectorySession::getSessionId, sessionId).last("FOR UPDATE"));
            if (winner == null || !userId.equals(winner.getUserId())) throw new BusinessException("轨迹会话编号已被占用");
            return winner;
        }
        log.info("[Trajectory] 开始轨迹会话: sessionId={}, userId={}", sessionId, userId);
        return session;
    }

    @Override
    @Transactional
    public TrajectorySession finishSession(String sessionId, Long endTime, Integer durationSec) {
        TrajectorySession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<TrajectorySession>().eq(TrajectorySession::getSessionId, sessionId));
        if (session == null) {
            throw new BusinessException("轨迹会话不存在: " + sessionId);
        }
        if (session.getStatus() == 1) {
            return session;
        }

        List<LocationHistory> points = historyMapper.selectList(
                new LambdaQueryWrapper<LocationHistory>()
                        .eq(LocationHistory::getSessionId, sessionId)
                        .orderByAsc(LocationHistory::getRecordedAt));

        // 结束时间优先用客户端"停止记录"的时刻，与开始时间同口径。
        LocalDateTime end = (endTime != null && endTime > 0)
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(endTime), ZONE)
                : LocalDateTime.now(ZONE);
        int pointCount = points.size();
        // 时长优先用客户端计时器上报值（与用户看到的一致）；为空才回退 start→end，
        // 避免 GPS 锁定延迟或丢点把时长压短。
        int durSec = (durationSec != null && durationSec >= 0)
                ? durationSec
                : (int) java.time.Duration.between(session.getStartTime(), end).getSeconds();
        double distanceKm = 0;

        if (pointCount > 1) {
            for (int i = 1; i < pointCount; i++) {
                distanceKm += haversineKm(
                        points.get(i - 1).getLatitude(), points.get(i - 1).getLongitude(),
                        points.get(i).getLatitude(), points.get(i).getLongitude());
            }
        }

        session.setEndTime(end);
        session.setPointCount(pointCount);
        session.setDurationSec(durSec);
        session.setDistanceKm(Math.round(distanceKm * 1000.0) / 1000.0);
        session.setStatus(1);
        sessionMapper.updateById(session);

        log.info("[Trajectory] 结束轨迹会话: sessionId={}, 点数={}, 里程={}km, 时长={}s",
                sessionId, pointCount, distanceKm, durSec);
        return session;
    }

    @Override
    public TrajectorySession getSession(String sessionId) {
        TrajectorySession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<TrajectorySession>()
                        .eq(TrajectorySession::getSessionId, sessionId));
        if (session == null) {
            throw new BusinessException("轨迹会话不存在: " + sessionId);
        }
        return session;
    }

    @Override
    public List<TrajectorySession> listSessions(Long userId) {
        return sessionMapper.selectList(
                new LambdaQueryWrapper<TrajectorySession>()
                        .eq(TrajectorySession::getUserId, userId)
                        .eq(TrajectorySession::getStatus, 1)
                        .orderByDesc(TrajectorySession::getStartTime));
    }

    @Override
    public List<LocationHistory> getHistoryBySession(String sessionId) {
        return historyMapper.selectList(
                new LambdaQueryWrapper<LocationHistory>()
                        .eq(LocationHistory::getSessionId, sessionId)
                        .orderByAsc(LocationHistory::getRecordedAt));
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        // 先删轨迹点再删会话：同一事务内避免留下无会话归属的孤点。
        historyMapper.delete(
                new LambdaQueryWrapper<LocationHistory>()
                        .eq(LocationHistory::getSessionId, sessionId));
        sessionMapper.delete(
                new LambdaQueryWrapper<TrajectorySession>()
                        .eq(TrajectorySession::getSessionId, sessionId));
        log.info("[Trajectory] 删除轨迹会话: sessionId={}", sessionId);
    }

    @Override
    public LocationVO getUserLocation(Long userId) {
        UserLocation loc = locationMapper.selectOne(
                new LambdaQueryWrapper<UserLocation>().eq(UserLocation::getUserId, userId));
        if (loc == null) {
            throw new BusinessException("该用户未上报位置");
        }
        User user = userMapper.selectById(userId);
        return LocationVO.from(loc, user != null ? user.getUsername() : null);
    }

    @Override
    public List<LocationVO> getGroupLocations(Long groupId) {
        List<GroupMember> members = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getGroupId, groupId));
        List<Long> userIds = members.stream().map(GroupMember::getUserId).distinct().toList();
        if (userIds.isEmpty()) return Collections.emptyList();

        List<UserLocation> locations = locationMapper.selectList(
                new LambdaQueryWrapper<UserLocation>().in(UserLocation::getUserId, userIds));
        if (locations.isEmpty()) return Collections.emptyList();

        Map<Long, String> usernameMap = userMapper.selectByIds(userIds).stream()
                .collect(Collectors.toMap(User::getUserId, User::getUsername, (left, right) -> left));

        return locations.stream()
                .map(loc -> LocationVO.from(loc, usernameMap.get(loc.getUserId())))
                .toList();
    }

    @Override
    public List<LocationHistory> getHistory(Long userId, String date) {
        LocalDate localDate = LocalDate.parse(date);
        LocalDateTime start = localDate.atStartOfDay();
        LocalDateTime end = localDate.atTime(LocalTime.MAX);
        return historyMapper.selectList(
                new LambdaQueryWrapper<LocationHistory>()
                        .eq(LocationHistory::getUserId, userId)
                        .between(LocationHistory::getRecordedAt, start, end)
                        .orderByAsc(LocationHistory::getRecordedAt));
    }

    @Override
    public List<String> getAvailableDates(Long userId, int days) {
        return historyMapper.selectAvailableDates(userId, days);
    }

    /** Haversine 公式计算两点球面距离（km）。 */
    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double sinLat = Math.sin(dLat / 2);
        double sinLng = Math.sin(dLng / 2);
        double a = sinLat * sinLat + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLng * sinLng;
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
