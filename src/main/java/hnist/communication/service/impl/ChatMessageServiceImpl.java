package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.entity.ChatMessage;
import hnist.communication.mapper.ChatMessageMapper;
import hnist.communication.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import hnist.communication.exception.BusinessException;

/** 单聊消息的幂等写入、会话历史与离线补拉。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl implements ChatMessageService {

    /** listReceived 的默认/最大条数上限（防一次拉爆） */
    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 2000;

    private final ChatMessageMapper chatMessageMapper;
    private final hnist.communication.service.ChatQuoteService quotes;

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ChatMessage create(ChatMessage message) {
        if (message.getReceiverId() == null || message.getReceiverId() <= 0 || message.getContent() == null || message.getContent().isBlank())
            throw new BusinessException("接收者和消息内容不能为空");
        if (message.getFp() != null && message.getFp().length() > 100)
            throw new BusinessException("消息标识过长");
        // 消息类型只允许 image/video/audio，其他值（含空）按 text 落库，避免未定义类型流入前端。
        message.setMsgType(Set.of("image", "video", "audio").contains(message.getMsgType() == null ? "" : message.getMsgType())
            ? message.getMsgType() : "text");
        // fp 是客户端重试幂等键：同发送者/接收者/fp 已存在时直接返回原消息，不再插入。
        if (message.getFp() != null && !message.getFp().isBlank()) {
            ChatMessage existing = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSenderId, message.getSenderId())
                .eq(ChatMessage::getReceiverId, message.getReceiverId())
                .eq(ChatMessage::getFp, message.getFp()).last("LIMIT 1"));
            if (existing != null) { quotes.fillPeers(List.of(existing)); return existing; }
        }
        quotes.validatePeer(message.getSenderId(), message.getReceiverId(), message.getQuoteMessageId());
        // validatePeer 已对原消息加 FOR UPDATE 行锁；锁内复查 fp 可串行化并发重试，READ_COMMITTED 保证看到锁等待期间提交的记录。
        if (message.getQuoteMessageId() != null && message.getFp() != null && !message.getFp().isBlank()) {
            ChatMessage existing = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSenderId, message.getSenderId()).eq(ChatMessage::getReceiverId, message.getReceiverId())
                .eq(ChatMessage::getFp, message.getFp()).last("LIMIT 1"));
            if (existing != null) { quotes.fillPeers(List.of(existing)); return existing; }
        }
        // 忽略客户端回传的 id/quote/recalledAt，防止伪造引用关系或撤回状态。
        message.setQuote(null);
        message.setId(null);
        message.setRecalledAt(null);
        // PC 管理端可能不带 fp，而客户端推送与历史合并都按 fp 判重；缺省时补服务端指纹，避免重进会话出现重复气泡。
        if (message.getFp() == null || message.getFp().isBlank()) {
            message.setFp("srv-" + UUID.randomUUID().toString().replace("-", ""));
        }
        message.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(message);
        quotes.fillPeers(List.of(message));
        quotes.notifyPeer(message);
        return message;
    }

    @Override
    public Page<ChatMessage> listMessages(Long userId1, Long userId2, int page, int size) {
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        // 双向查询：A→B 和 B→A
        wrapper.and(w -> w
            .and(inner -> inner.eq(ChatMessage::getSenderId, userId1).eq(ChatMessage::getReceiverId, userId2))
            .or(inner -> inner.eq(ChatMessage::getSenderId, userId2).eq(ChatMessage::getReceiverId, userId1))
        );
        // 只过滤当前用户自己的软删除记录：chat_message_actions 按 user_id 记录，删除不影响对方历史。
        wrapper.apply("NOT EXISTS (SELECT 1 FROM chat_message_actions a WHERE a.is_deleted=0 AND a.chat_type='peer' " +
            "AND a.action='delete' AND a.message_id=chat_messages.id AND a.user_id={0})", userId1);
        wrapper.orderByAsc(ChatMessage::getCreatedAt).orderByAsc(ChatMessage::getId);
        // 单页上限 200 条，防止客户端传入过大的 size 拖垮查询。
        Page<ChatMessage> result = chatMessageMapper.selectPage(new Page<>(Math.max(1, page), Math.min(200, Math.max(1, size))), wrapper);
        quotes.fillPeers(result.getRecords());
        return result;
    }

    /**
     * 按接收者拉取消息，供客户端离线补未读。
     * 必须倒序 + LIMIT 取最近 N 条再翻回正序；升序 + LIMIT 会先取到最老的消息，长期未登录的账号反而拉不到新消息。
     */
    @Override
    public List<ChatMessage> listReceived(Long receiverId, LocalDateTime since, int limit) {
        if (receiverId == null || receiverId <= 0) return Collections.emptyList();
        // limit 非正数走默认上限，并强制不超过 MAX_LIMIT。
        int n = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getReceiverId, receiverId);
        // 自己发给自己的消息不算"收到的消息"（自聊回显），否则会给自己计未读
        wrapper.ne(ChatMessage::getSenderId, receiverId);
        wrapper.isNull(ChatMessage::getRecalledAt);
        wrapper.apply("NOT EXISTS (SELECT 1 FROM chat_message_actions a WHERE a.is_deleted=0 AND a.chat_type='peer' " +
            "AND a.action='delete' AND a.message_id=chat_messages.id AND a.user_id={0})", receiverId);
        if (since != null) wrapper.ge(ChatMessage::getCreatedAt, since);
        wrapper.orderByDesc(ChatMessage::getCreatedAt);
        wrapper.last("LIMIT " + n);

        List<ChatMessage> list = chatMessageMapper.selectList(wrapper);
        quotes.fillPeers(list);
        Collections.reverse(list);
        return list;
    }
}
