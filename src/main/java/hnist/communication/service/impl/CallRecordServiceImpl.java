package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.CallRecord;
import hnist.communication.entity.User;
import hnist.communication.mapper.CallRecordMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.CallRecordService;
import hnist.communication.vo.CallRecordVO;
import hnist.communication.vo.CallStatsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/** 通话记录的分页检索、幂等写入与统计汇总。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallRecordServiceImpl implements CallRecordService {

    private static final int MAX_PAGE_SIZE = 200;

    /** 查询无匹配结果时使用的占位 ID：eq(id, -1) 让分页返回空，而不是丢掉筛选条件。 */
    private static final Long NON_EXISTENT_ID = -1L;
    /** 通话状态：已完成 */
    private static final String STATUS_COMPLETED = "completed";

    private final CallRecordMapper callRecordMapper;
    private final UserMapper userMapper;

    @Override
    public Page<CallRecordVO> list(int page, int size, Integer mediaType, Integer callType,
                                    String status, String keyword, String startTime, String endTime,
                                    Long viewerId, boolean admin) {
        Page<CallRecord> pageParam = new Page<>(Math.max(1, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size)));
        LambdaQueryWrapper<CallRecord> wrapper = new LambdaQueryWrapper<>();

        // 非管理员只能看自己主叫或被叫的记录：归属条件直接进入分页查询，防止越权读取全量通话。
        if (!admin) {
            wrapper.and(w -> w.eq(CallRecord::getCallerId, viewerId)
                    .or().eq(CallRecord::getCalleeId, viewerId));
        }

        if (mediaType != null) wrapper.eq(CallRecord::getMediaType, mediaType);
        if (callType != null) wrapper.eq(CallRecord::getCallType, callType);
        if (status != null && !status.isEmpty()) wrapper.eq(CallRecord::getStatus, status);
        if (startTime != null && !startTime.isEmpty())
            wrapper.ge(CallRecord::getStartTime, startTime);
        if (endTime != null && !endTime.isEmpty())
            wrapper.le(CallRecord::getStartTime, endTime);
        if (keyword != null && !keyword.isEmpty()) {
            Set<Long> matchedUserIds = userMapper.selectList(
                    new LambdaQueryWrapper<User>()
                            .like(User::getUsername, keyword)
                            .or().like(User::getNickname, keyword)
            ).stream().map(User::getUserId).collect(Collectors.toSet());
            if (!matchedUserIds.isEmpty()) {
                wrapper.and(w -> w.in(CallRecord::getCallerId, matchedUserIds)
                        .or().in(CallRecord::getCalleeId, matchedUserIds));
            } else {
                // 关键词没有匹配到用户时必须让结果为空；跳过该条件会退化成全量查询。
                wrapper.eq(CallRecord::getId, NON_EXISTENT_ID);
            }
        }
        wrapper.orderByDesc(CallRecord::getCreatedAt);

        Page<CallRecord> result = callRecordMapper.selectPage(pageParam, wrapper);

        // 批量查用户名
        Map<Long, String> usernameMap = buildUsernameMap(result.getRecords());

        return (Page<CallRecordVO>) result.convert(r ->
                CallRecordVO.from(r, usernameMap.get(r.getCallerId()), usernameMap.get(r.getCalleeId())));
    }

    @Override
    public CallRecordVO get(Long id) {
        CallRecord record = callRecordMapper.selectById(id);
        if (record == null) throw new BusinessException("通话记录不存在");
        return CallRecordVO.from(record, getUsername(record.getCallerId()), getUsername(record.getCalleeId()));
    }

    @Override
    @Transactional
    public CallRecordVO create(CallRecord record) {
        if (record.getRoomId() == null || record.getRoomId().isEmpty())
            record.setRoomId(UUID.randomUUID().toString());
        // roomId 是客户端离线补传/重试的幂等键。已存在时只更新结束状态，避免断网重试产生重复记录。
        CallRecord existing = callRecordMapper.selectOne(new LambdaQueryWrapper<CallRecord>()
                .eq(CallRecord::getRoomId, record.getRoomId()));
        if (existing != null) {
            // roomId 已绑定其他主叫时拒绝覆盖，防止用同一房间号篡改他人通话记录。
            if (!Objects.equals(existing.getCallerId(), record.getCallerId())) {
                throw new BusinessException("通话房间号已被其他用户占用");
            }
            if (record.getCalleeId() != null) existing.setCalleeId(record.getCalleeId());
            if (record.getMediaType() != null) existing.setMediaType(record.getMediaType());
            if (record.getCallType() != null) existing.setCallType(record.getCallType());
            if (record.getStartTime() != null) existing.setStartTime(record.getStartTime());
            if (record.getEndTime() != null) existing.setEndTime(record.getEndTime());
            if (record.getDurationSeconds() != null) existing.setDurationSeconds(record.getDurationSeconds());
            if (record.getStatus() != null) existing.setStatus(record.getStatus());
            if (record.getRecordFileUrl() != null) existing.setRecordFileUrl(record.getRecordFileUrl());
            callRecordMapper.updateById(existing);
            return CallRecordVO.from(existing, getUsername(existing.getCallerId()), getUsername(existing.getCalleeId()));
        }
        callRecordMapper.insert(record);
        return CallRecordVO.from(record, getUsername(record.getCallerId()), getUsername(record.getCalleeId()));
    }

    @Override
    @Transactional
    public CallRecordVO update(Long id, CallRecord record) {
        CallRecord exist = callRecordMapper.selectById(id);
        if (exist == null) throw new BusinessException("通话记录不存在");

        if (record.getRoomId() != null) exist.setRoomId(record.getRoomId());
        if (record.getCallerId() != null) exist.setCallerId(record.getCallerId());
        if (record.getCalleeId() != null) exist.setCalleeId(record.getCalleeId());
        if (record.getMediaType() != null) exist.setMediaType(record.getMediaType());
        if (record.getCallType() != null) exist.setCallType(record.getCallType());
        if (record.getStartTime() != null) exist.setStartTime(record.getStartTime());
        if (record.getEndTime() != null) exist.setEndTime(record.getEndTime());
        if (record.getDurationSeconds() != null) exist.setDurationSeconds(record.getDurationSeconds());
        if (record.getStatus() != null) exist.setStatus(record.getStatus());
        if (record.getRecordFileUrl() != null) exist.setRecordFileUrl(record.getRecordFileUrl());

        callRecordMapper.updateById(exist);
        return CallRecordVO.from(exist, getUsername(exist.getCallerId()), getUsername(exist.getCalleeId()));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (callRecordMapper.selectById(id) == null) throw new BusinessException("通话记录不存在");
        callRecordMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void batchDelete(Long[] ids) {
        callRecordMapper.deleteByIds(Arrays.asList(ids));
    }

    @Override
    public CallStatsVO stats() {
        long totalCount = callRecordMapper.selectCount(null);

        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        long todayCount = callRecordMapper.selectCount(
                new LambdaQueryWrapper<CallRecord>().between(CallRecord::getCreatedAt, todayStart, todayEnd));

        long videoCount = callRecordMapper.selectCount(
                new LambdaQueryWrapper<CallRecord>().eq(CallRecord::getMediaType, 2));
        double videoRatio = totalCount > 0 ? (double) videoCount / totalCount * 100 : 0;

        // 平均时长只统计 completed 且 durationSeconds 非空的记录，避免未接通或缺失时长拉低均值。
        List<CallRecord> completed = callRecordMapper.selectList(
                new LambdaQueryWrapper<CallRecord>()
                        .eq(CallRecord::getStatus, STATUS_COMPLETED)
                        .isNotNull(CallRecord::getDurationSeconds));
        double avgDuration = completed.isEmpty() ? 0
                : completed.stream().mapToInt(CallRecord::getDurationSeconds).average().orElse(0);

        return CallStatsVO.builder()
                .totalCount(totalCount).todayCount(todayCount)
                .videoRatio(videoRatio).avgDuration(avgDuration).build();
    }

    // ---- 辅助方法 ----

    private String getUsername(Long userId) {
        if (userId == null || userId <= 0) return null;
        User user = userMapper.selectById(userId);
        return user != null ? user.getUsername() : null;
    }

    private Map<Long, String> buildUsernameMap(List<CallRecord> records) {
        Set<Long> userIds = new HashSet<>();
        for (CallRecord r : records) {
            if (r.getCallerId() != null && r.getCallerId() > 0) userIds.add(r.getCallerId());
            if (r.getCalleeId() != null && r.getCalleeId() > 0) userIds.add(r.getCalleeId());
        }
        if (userIds.isEmpty()) return Collections.emptyMap();
        return userMapper.selectByIds(userIds).stream()
                .collect(Collectors.toMap(User::getUserId, User::getUsername, (left, right) -> left));
    }
}
