package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.exception.BusinessException;
import hnist.communication.common.TransactionUtils;
import hnist.communication.dto.GroupMessageRequest;
import hnist.communication.entity.Group;
import hnist.communication.entity.GroupMessage;
import hnist.communication.entity.User;
import hnist.communication.mapper.GroupMapper;
import hnist.communication.mapper.GroupMessageMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.GroupMessageService;
import hnist.communication.vo.GroupMessageVO;
import hnist.communication.vo.GroupUnreadVO;
import hnist.communication.websocket.LocationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 群消息的发送、历史与未读查询及实时推送。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupMessageServiceImpl implements GroupMessageService {

    /** 时间格式化（WebSocket 推送用，handler 内部 ObjectMapper 未注册 JavaTimeModule） */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GroupMessageMapper groupMessageMapper;
    private final GroupMapper groupMapper;
    private final UserMapper userMapper;
    private final LocationWebSocketHandler locationWebSocketHandler;
    private final hnist.communication.service.ChatQuoteService quotes;

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public GroupMessageVO send(GroupMessageRequest req) {
        User sender = userMapper.selectById(req.getSenderId());
        if (sender == null) {
            throw new BusinessException("发送者用户不存在");
        }
        Group group = groupMapper.selectById(req.getGroupId());
        if (group == null) {
            throw new BusinessException("群组不存在");
        }
        // 仅群成员可发送：以 users.default_group_id 判断，不额外校验管理员角色。
        if (!req.getGroupId().equals(sender.getGroupId())) {
            throw new BusinessException("您不是该群成员，不能发送消息");
        }

        // fp 是客户端重试幂等键：同发送者/群/fp 已存在时直接返回原消息，不重复入库。
        if (req.getFp() != null && !req.getFp().isBlank()) {
            GroupMessage existing = groupMessageMapper.selectOne(new LambdaQueryWrapper<GroupMessage>()
                .eq(GroupMessage::getSenderId, req.getSenderId()).eq(GroupMessage::getGroupId, req.getGroupId())
                .eq(GroupMessage::getFp, req.getFp()).last("LIMIT 1"));
            if (existing != null) { quotes.fillGroups(List.of(existing)); return GroupMessageVO.from(existing); }
        }
        quotes.validateGroup(req.getSenderId(), req.getGroupId(), req.getQuoteMessageId());
        // 引用原消息已由 validateGroup 加 FOR UPDATE 行锁，在锁内复查 fp 可挡住并发重试。
        if (req.getQuoteMessageId() != null && req.getFp() != null && !req.getFp().isBlank()) {
            GroupMessage existing = groupMessageMapper.selectOne(new LambdaQueryWrapper<GroupMessage>()
                .eq(GroupMessage::getSenderId, req.getSenderId()).eq(GroupMessage::getGroupId, req.getGroupId())
                .eq(GroupMessage::getFp, req.getFp()).last("LIMIT 1"));
            if (existing != null) { quotes.fillGroups(List.of(existing)); return GroupMessageVO.from(existing); }
        }
        GroupMessage msg = GroupMessage.builder()
                .groupId(req.getGroupId())
                .senderId(req.getSenderId())
                .senderName(sender.getUsername())
                .content(req.getContent())
                .msgType(normalizeMsgType(req.getMsgType()))
                .fp(req.getFp()).quoteMessageId(req.getQuoteMessageId())
                .createdAt(LocalDateTime.now())
                .build();
        groupMessageMapper.insert(msg);

        quotes.fillGroups(List.of(msg));
        GroupMessageVO vo = GroupMessageVO.from(msg);
        vo.setGroupName(group.getName());

        // 实时推送给全群（含发送者自己，客户端自行过滤回声）
        // 推送失败不能回滚已入库的消息：放到 afterCommit 执行，异常只记日志，接收方靠拉取/重连补收。
        TransactionUtils.afterCommit(() -> {
            try {
                GroupMessage fresh = groupMessageMapper.selectById(msg.getId());
                if (fresh == null || fresh.getRecalledAt() != null) return;
                quotes.fillGroups(List.of(fresh));
                GroupMessageVO event = GroupMessageVO.from(fresh); event.setGroupName(group.getName());
                pushTextBroadcast(event);
            } catch (Exception e) { log.error("群消息推送失败: msgId={}", vo.getId(), e); }
        });
        return vo;
    }

    @Override
    public Page<GroupMessageVO> listByGroup(Long groupId, Long requesterId, int page, int size) {
        // 群成员或超级管理员可查看
        if (!isSuperAdmin(requesterId)) {
            User requester = requesterId == null ? null : userMapper.selectById(requesterId);
            if (requester == null || !groupId.equals(requester.getGroupId())) {
                throw new BusinessException("您不是该群成员，不能查看聊天记录");
            }
        }

        Page<GroupMessage> result = groupMessageMapper.selectPage(new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size))),
                new LambdaQueryWrapper<GroupMessage>()
                        .eq(GroupMessage::getGroupId, groupId)
                        .apply("NOT EXISTS (SELECT 1 FROM chat_message_actions a WHERE a.is_deleted=0 AND a.chat_type='group' " +
                            "AND a.action='delete' AND a.message_id=group_messages.id AND a.user_id={0})", requesterId)
                        .orderByDesc(GroupMessage::getCreatedAt).orderByDesc(GroupMessage::getId));

        Group group = groupMapper.selectById(groupId);
        String groupName = group != null ? group.getName() : null;
        quotes.fillGroups(result.getRecords());
        return (Page<GroupMessageVO>) result.convert(m -> {
            GroupMessageVO vo = GroupMessageVO.from(m);
            vo.setGroupName(groupName);
            return vo;
        });
    }

    /** 单群未读扫描上限（只为给红点用，超出的部分截断成"至少这么多"） */
    private static final int UNREAD_SCAN_LIMIT = 200;

    /**
     * 返回用户所在群的未读数与最近消息时间，供离线重连补红点。
     * 群归属以 users.default_group_id 为判据；lastMessageAt 供客户端裁剪没有本地已读标记时的兜底窗口。
     */
    @Override
    public List<GroupUnreadVO> listUnreadByUser(Long userId, LocalDateTime since) {
        if (userId == null || userId <= 0) {
            return Collections.emptyList();
        }
        // 群成员关系与 send/listByGroup 同一判据（users.default_group_id），避免两套口径打架
        User user = userMapper.selectById(userId);
        if (user == null || user.getGroupId() == null) {
            return Collections.emptyList();
        }
        Long gid = user.getGroupId();
        Group group = groupMapper.selectById(gid);

        // 只统计别人发到该群、since 之后且未撤回/未被我删除的消息。
        // LIMIT 只影响未读数是否被截断，不影响"有没有红点"。
        LambdaQueryWrapper<GroupMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(GroupMessage::getGroupId, gid)
                .ne(GroupMessage::getSenderId, userId).isNull(GroupMessage::getRecalledAt)
                .apply("NOT EXISTS (SELECT 1 FROM chat_message_actions a WHERE a.is_deleted=0 AND a.chat_type='group' " +
                    "AND a.action='delete' AND a.message_id=group_messages.id AND a.user_id={0})", userId);
        if (since != null) {
            wrapper.ge(GroupMessage::getCreatedAt, since);
        }
        wrapper.orderByDesc(GroupMessage::getCreatedAt)
                .last("LIMIT " + UNREAD_SCAN_LIMIT);
        List<GroupMessage> received = groupMessageMapper.selectList(wrapper);

        // 最近一条消息的时间（含自己发的）：给客户端做窗口裁剪用
        List<GroupMessage> latestList = groupMessageMapper.selectList(
                new LambdaQueryWrapper<GroupMessage>()
                        .eq(GroupMessage::getGroupId, gid)
                        .orderByDesc(GroupMessage::getCreatedAt)
                        .last("LIMIT 1"));
        LocalDateTime lastMessageAt = latestList.isEmpty() ? null : latestList.get(0).getCreatedAt();

        return Collections.singletonList(GroupUnreadVO.builder()
                .groupId(gid)
                .groupName(group != null ? group.getName() : null)
                .unreadCount(received.size())
                .lastMessageAt(lastMessageAt)
                .build());
    }

    @Override
    public Page<GroupMessageVO> listAll(int page, int size, Long groupId, String keyword) {
        LambdaQueryWrapper<GroupMessage> wrapper = new LambdaQueryWrapper<>();
        if (groupId != null) {
            wrapper.eq(GroupMessage::getGroupId, groupId);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(GroupMessage::getContent, keyword);
        }
        wrapper.orderByDesc(GroupMessage::getCreatedAt);
        Page<GroupMessage> result = groupMessageMapper.selectPage(new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size))), wrapper);

        // 批量查群名，避免 N+1
        List<Long> groupIds = result.getRecords().stream()
                .map(GroupMessage::getGroupId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> groupNameMap = groupIds.isEmpty() ? Collections.emptyMap()
                : groupMapper.selectByIds(groupIds).stream()
                .collect(Collectors.toMap(Group::getGroupId, Group::getName, (left, right) -> left));

        quotes.fillGroups(result.getRecords());
        return (Page<GroupMessageVO>) result.convert(m -> {
            GroupMessageVO vo = GroupMessageVO.from(m);
            vo.setGroupName(groupNameMap.get(m.getGroupId()));
            return vo;
        });
    }

    @Override
    public void delete(Long id, Long operatorId) {
        if (!isSuperAdmin(operatorId)) {
            throw new BusinessException("仅超级管理员可删除聊天记录");
        }
        groupMessageMapper.deleteById(id);
    }

    // ---- 辅助方法 ----

    private String normalizeMsgType(String msgType) {
        if (msgType == null) return "text";
        String t = msgType.trim().toLowerCase();
        return ("image".equals(t) || "video".equals(t) || "audio".equals(t)) ? t : "text";
    }

    private void pushTextBroadcast(GroupMessageVO vo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", vo.getId());
        data.put("fp", vo.getFp());
        data.put("quoteMessageId", vo.getQuoteMessageId());
        data.put("quote", vo.getQuote());
        data.put("groupId", vo.getGroupId());
        data.put("senderId", vo.getSenderId());
        data.put("senderName", vo.getSenderName());
        // 广播必须带 groupName，客户端横幅才能显示「群名 · 发送者」；仅有 senderName 无法区分群。
        data.put("groupName", vo.getGroupName());
        data.put("content", vo.getContent());
        data.put("msgType", vo.getMsgType() == null ? "text" : vo.getMsgType());
        data.put("createdAt", vo.getCreatedAt() == null ? null : vo.getCreatedAt().format(TIME_FMT));
        locationWebSocketHandler.pushToGroup(vo.getGroupId(), Map.of("type", "text_broadcast", "data", data));
    }

    /** 判断是否为超级管理员 */
    private boolean isSuperAdmin(Long userId) {
        if (userId == null) return false;
        User user = userMapper.selectById(userId);
        return user != null && User.SUPER_ADMIN_USERNAME.equals(user.getUsername())
                && User.isSuperAdmin(user.getRole());
    }
}
