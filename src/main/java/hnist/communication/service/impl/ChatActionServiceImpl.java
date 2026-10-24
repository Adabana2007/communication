package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.*;
import hnist.communication.mapper.*;
import hnist.communication.service.ChatActionService;
import hnist.communication.websocket.LocationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** 消息删除/撤回：校验归属、按幂等键写入动作事件，并在事务提交后经 WebSocket 推送。 */
@Service
@RequiredArgsConstructor
public class ChatActionServiceImpl implements ChatActionService {
    private final ChatMessageMapper peers;
    private final GroupMessageMapper groups;
    private final ChatMessageActionMapper actions;
    private final UserMapper users;
    private final LocationWebSocketHandler sockets;

    /** 旧群聊缓存没有fp：仅当会话内发送者/内容/时间唯一匹配时返回ID，绝不猜测重复消息。 */
    @Override
    public Long resolveLegacy(Long userId, String type, Long conversation, Long sender, Long timestamp, String content) {
        if (conversation == null || sender == null || timestamp == null || timestamp <= 0 || content == null || content.length() > 500)
            throw new BusinessException("历史消息标识不完整，请刷新后重试");
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        if ("peer".equals(type)) {
            List<ChatMessage> found = peers.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSenderId, sender).eq(ChatMessage::getContent, content)
                .between(ChatMessage::getCreatedAt, time.minusSeconds(3), time.plusSeconds(3))
                .and(w -> w.and(p -> p.eq(ChatMessage::getSenderId, userId).eq(ChatMessage::getReceiverId, conversation))
                    .or(p -> p.eq(ChatMessage::getSenderId, conversation).eq(ChatMessage::getReceiverId, userId)))
                .last("LIMIT 2"));
            return found.size() == 1 ? found.get(0).getId() : null;
        }
        if (!"group".equals(type)) throw new BusinessException("无效的会话类型");
        User actor = users.selectById(userId);
        if (actor == null || !conversation.equals(actor.getGroupId())) throw new BusinessException("您不是该群成员");
        List<GroupMessage> found = groups.selectList(new LambdaQueryWrapper<GroupMessage>()
            .eq(GroupMessage::getGroupId, conversation).eq(GroupMessage::getSenderId, sender)
            .eq(GroupMessage::getContent, content).between(GroupMessage::getCreatedAt, time.minusSeconds(3), time.plusSeconds(3))
            .last("LIMIT 2"));
        return found.size() == 1 ? found.get(0).getId() : null;
    }

    /** 实时单聊先到、HTTP入库后到时，按指纹解析ID；范围始终限制在本人会话内。 */
    @Override
    public Long resolve(Long userId, String type, Long conversation, String fp) {
        if (conversation == null || fp == null || fp.isBlank() || fp.length() > 100)
            throw new BusinessException("无效的消息标识");
        if ("peer".equals(type)) {
            List<ChatMessage> found = peers.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getFp, fp).and(w -> w
                    .and(p -> p.eq(ChatMessage::getSenderId, userId).eq(ChatMessage::getReceiverId, conversation))
                    .or(p -> p.eq(ChatMessage::getSenderId, conversation).eq(ChatMessage::getReceiverId, userId)))
                .last("LIMIT 2"));
            return found.size() == 1 ? found.get(0).getId() : null;
        }
        if (!"group".equals(type)) throw new BusinessException("无效的会话类型");
        User actor = users.selectById(userId);
        if (actor == null || !conversation.equals(actor.getGroupId())) throw new BusinessException("您不是该群成员");
        List<GroupMessage> found = groups.selectList(new LambdaQueryWrapper<GroupMessage>()
            .eq(GroupMessage::getGroupId, conversation).eq(GroupMessage::getFp, fp).last("LIMIT 2"));
        return found.size() == 1 ? found.get(0).getId() : null;
    }

    /** 按自增 id 增量拉取本人的动作事件，单次上限 200 条供客户端分批补同步。 */
    @Override
    public List<ChatMessageAction> sync(Long userId, long after) {
        return actions.selectList(new LambdaQueryWrapper<ChatMessageAction>()
            .eq(ChatMessageAction::getUserId, userId).gt(ChatMessageAction::getId, Math.max(0, after))
            .orderByAsc(ChatMessageAction::getId).last("LIMIT 200"));
    }

    @Transactional
    @Override
    public ChatMessageAction operate(Long userId, String type, Long id, String action) {
        if (!("peer".equals(type) || "group".equals(type)) ||
            !("delete".equals(action) || "recall".equals(action)) || id == null || id <= 0) {
            throw new BusinessException("无效的消息操作");
        }
        User actor = users.selectById(userId);
        if (actor == null) throw new BusinessException("用户不存在");
        Long sender, peer = null, group = null;
        String fp;
        LocalDateTime created, recalled;
        if ("peer".equals(type)) {
            ChatMessage m = peers.selectById(id);
            if (m == null) throw new BusinessException("消息不存在");
            sender = m.getSenderId();
            // 单聊只有发送者或接收者能删除/撤回，避免凭消息 id 越权操作。
            if (!userId.equals(sender) && !userId.equals(m.getReceiverId()))
                throw new BusinessException("无权操作该消息");
            peer = userId.equals(sender) ? m.getReceiverId() : sender;
            fp = m.getFp(); created = m.getCreatedAt(); recalled = m.getRecalledAt();
        } else {
            GroupMessage m = groups.selectById(id);
            if (m == null) throw new BusinessException("消息不存在");
            group = m.getGroupId(); sender = m.getSenderId();
            if (!group.equals(actor.getGroupId())) throw new BusinessException("您不是该群成员");
            fp = m.getFp(); created = m.getCreatedAt(); recalled = m.getRecalledAt();
        }
        // 撤回只能操作自己发送的消息；删除只写本人的隐藏记录。
        if ("recall".equals(action) && !userId.equals(sender))
            throw new BusinessException("只能撤回自己发送的消息");
        // 同一用户对同一消息的同一操作只记一次，重复请求直接返回已有事件。
        ChatMessageAction existing = find(userId, type, id, action);
        if (existing != null) return existing;
        LocalDateTime now = LocalDateTime.now();
        if ("recall".equals(action) && recalled == null) {
            // 撤回窗口 2 分钟；UPDATE 再带 recalledAt IS NULL 与时间条件兜底并发。
            if (created == null || created.isBefore(now.minusMinutes(2)))
                throw new BusinessException("超过2分钟，不能撤回");
            int changed;
            if ("peer".equals(type)) {
                changed = peers.update(null, new LambdaUpdateWrapper<ChatMessage>()
                    .eq(ChatMessage::getId, id).eq(ChatMessage::getSenderId, userId)
                    .isNull(ChatMessage::getRecalledAt).ge(ChatMessage::getCreatedAt, now.minusMinutes(2))
                    .set(ChatMessage::getRecalledAt, now).set(ChatMessage::getContent, "")
                    .set(ChatMessage::getMsgType, "recalled"));
            } else {
                changed = groups.update(null, new LambdaUpdateWrapper<GroupMessage>()
                    .eq(GroupMessage::getId, id).eq(GroupMessage::getSenderId, userId)
                    .isNull(GroupMessage::getRecalledAt).ge(GroupMessage::getCreatedAt, now.minusMinutes(2))
                    .set(GroupMessage::getRecalledAt, now).set(GroupMessage::getContent, "")
                    .set(GroupMessage::getMsgType, "recalled"));
            }
            if (changed == 0) throw new BusinessException("消息状态已变化，请刷新后重试");
        }
        // 删除只影响本人记录，撤回需要通知会话内所有相关人同步状态。
        Set<Long> recipients = new HashSet<>();
        recipients.add(userId);
        if ("recall".equals(action)) {
            if (group == null) recipients.add(peer);
            else for (User member : users.selectList(new LambdaQueryWrapper<User>().eq(User::getGroupId, group)))
                recipients.add(member.getUserId());
        }
        for (Long recipient : recipients) {
            ChatMessageAction event = new ChatMessageAction();
            event.setUserId(recipient); event.setChatType(type); event.setMessageId(id); event.setFp(fp);
            event.setPeerId(group == null ? (recipient.equals(userId) ? peer : userId) : null);
            event.setGroupId(group); event.setAction(action); event.setActorId(userId);
            event.setActorName(actor.getNickname() == null || actor.getNickname().isBlank()
                ? actor.getUsername() : actor.getNickname());
            event.setCreatedAt(now);
            // uk_message_action 唯一键 + INSERT IGNORE 收敛重复事件；返回 0 表示已存在，不再推送。
            if (actions.insertOnce(event) > 0) {
                ChatMessageAction saved = find(recipient, type, id, action);
                // 事件在事务提交后才推送，避免推送成功但数据库回滚。
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { sockets.pushToUser(recipient, payload(saved)); }
                });
            }
        }
        return find(userId, type, id, action);
    }

    private ChatMessageAction find(Long userId, String type, Long id, String action) {
        return actions.selectOne(new LambdaQueryWrapper<ChatMessageAction>()
            .eq(ChatMessageAction::getUserId, userId).eq(ChatMessageAction::getChatType, type)
            .eq(ChatMessageAction::getMessageId, id).eq(ChatMessageAction::getAction, action));
    }

    private Map<String, Object> payload(ChatMessageAction a) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", a.getId()); data.put("userId", a.getUserId()); data.put("chatType", a.getChatType());
        data.put("messageId", a.getMessageId()); data.put("fp", a.getFp()); data.put("peerId", a.getPeerId());
        data.put("groupId", a.getGroupId()); data.put("action", a.getAction());
        data.put("actorId", a.getActorId()); data.put("actorName", a.getActorName());
        data.put("createdAt", a.getCreatedAt().toString());
        return Map.of("type", "message_action", "data", data);
    }
}
