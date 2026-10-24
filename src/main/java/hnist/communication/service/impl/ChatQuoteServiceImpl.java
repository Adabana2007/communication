package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.common.TransactionUtils;
import hnist.communication.entity.*;
import hnist.communication.mapper.*;
import hnist.communication.service.ChatQuoteService;
import hnist.communication.vo.MessageQuoteVO;
import hnist.communication.websocket.LocationWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 消息引用校验与引用快照填充，并在事务提交后推送私聊消息。 */
@Slf4j
@Service @RequiredArgsConstructor
public class ChatQuoteServiceImpl implements ChatQuoteService {
    private final ChatMessageMapper peers;
    private final GroupMessageMapper groups;
    private final UserMapper users;
    private final ChatMessageActionMapper actions;
    private final LocationWebSocketHandler sockets;

    private boolean samePeer(ChatMessage m, Long user, Long peer) {
        return (user.equals(m.getSenderId()) && peer.equals(m.getReceiverId())) ||
            (peer.equals(m.getSenderId()) && user.equals(m.getReceiverId()));
    }
    private void visible(Long user, String type, Long id) {
        // 已被该用户删除的消息不能再被引用或定位查看。
        if (actions.selectCount(new LambdaQueryWrapper<ChatMessageAction>()
            .eq(ChatMessageAction::getUserId, user).eq(ChatMessageAction::getChatType, type)
            .eq(ChatMessageAction::getMessageId, id).eq(ChatMessageAction::getAction, "delete")) > 0)
            throw new BusinessException("原消息已从你的记录中删除");
    }
    private void quotable(String type, Object recalled) {
        if (recalled != null || "recalled".equals(type)) throw new BusinessException("原消息已撤回");
        if (!Set.of("text", "image", "video", "audio").contains(type == null ? "text" : type))
            throw new BusinessException("该消息类型暂不支持引用");
    }
    @Override
    public void validatePeer(Long user, Long peer, Long id) {
        if (id == null) return;
        if (id <= 0) throw new BusinessException("无效的引用消息ID");
        // 与撤回的 UPDATE 共用行锁，防止校验和创建之间原消息被撤回。
        ChatMessage source = peers.selectOne(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getId, id).last("FOR UPDATE"));
        if (source == null || !samePeer(source, user, peer)) throw new BusinessException("引用消息不属于当前会话");
        visible(user, "peer", id); quotable(source.getMsgType(), source.getRecalledAt());
    }
    @Override
    public void validateGroup(Long user, Long group, Long id) {
        if (id == null) return;
        if (id <= 0) throw new BusinessException("无效的引用消息ID");
        GroupMessage source = groups.selectOne(new LambdaQueryWrapper<GroupMessage>().eq(GroupMessage::getId, id).last("FOR UPDATE"));
        if (source == null || !group.equals(source.getGroupId())) throw new BusinessException("引用消息不属于当前群组");
        visible(user, "group", id); quotable(source.getMsgType(), source.getRecalledAt());
    }
    private MessageQuoteVO unavailable(Long id) {
        return MessageQuoteVO.builder().messageId(id).status("unavailable").msgType("text").content("").build();
    }
    private MessageQuoteVO view(Long id, String fp, Long sender, String name, String type, String content, Object recalled) {
        boolean gone = recalled != null || "recalled".equals(type);
        return MessageQuoteVO.builder().messageId(id).fp(fp).senderId(sender).senderName(name)
            .status(gone ? "recalled" : "available").msgType(gone ? "recalled" : type)
            .content(gone ? "" : content).build();
    }
    /** 批量读取原文，不持久化内容快照，历史/离线查询自动反映撤回。 */
    @Override
    public void fillPeers(List<ChatMessage> rows) {
        Set<Long> ids = rows.stream().map(ChatMessage::getQuoteMessageId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ChatMessage> sources = ids.isEmpty() ? Map.of() : peers.selectByIds(ids).stream()
            .collect(Collectors.toMap(ChatMessage::getId, Function.identity(), (left, right) -> left));
        Set<Long> senders = sources.values().stream().map(ChatMessage::getSenderId).collect(Collectors.toSet());
        Map<Long, User> names = senders.isEmpty() ? Map.of() : users.selectByIds(senders).stream()
            .collect(Collectors.toMap(User::getUserId, Function.identity(), (left, right) -> left));
        for (ChatMessage row : rows) {
            row.setQuote(null);
            if (row.getQuoteMessageId() == null || row.getRecalledAt() != null || "recalled".equals(row.getMsgType())) continue;
            ChatMessage src = sources.get(row.getQuoteMessageId());
            if (src == null || !samePeer(src, row.getSenderId(), row.getReceiverId())) row.setQuote(unavailable(row.getQuoteMessageId()));
            else {
                User sender = names.get(src.getSenderId());
                String name = sender == null ? "用户" + src.getSenderId() :
                    (sender.getNickname() == null || sender.getNickname().isBlank() ? sender.getUsername() : sender.getNickname());
                row.setQuote(view(src.getId(), src.getFp(), src.getSenderId(), name, src.getMsgType(), src.getContent(), src.getRecalledAt()));
            }
        }
    }
    @Override
    public void fillGroups(List<GroupMessage> rows) {
        Set<Long> ids = rows.stream().map(GroupMessage::getQuoteMessageId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, GroupMessage> sources = ids.isEmpty() ? Map.of() : groups.selectByIds(ids).stream()
            .collect(Collectors.toMap(GroupMessage::getId, Function.identity(), (left, right) -> left));
        for (GroupMessage row : rows) {
            row.setQuote(null);
            if (row.getQuoteMessageId() == null || row.getRecalledAt() != null || "recalled".equals(row.getMsgType())) continue;
            GroupMessage src = sources.get(row.getQuoteMessageId());
            row.setQuote(src == null || !row.getGroupId().equals(src.getGroupId()) ? unavailable(row.getQuoteMessageId()) :
                view(src.getId(), src.getFp(), src.getSenderId(), src.getSenderName(), src.getMsgType(), src.getContent(), src.getRecalledAt()));
        }
    }
    @Override
    public ChatMessage locatePeer(Long user, Long peer, Long id) {
        ChatMessage m = peers.selectById(id);
        if (m == null || !samePeer(m, user, peer)) throw new BusinessException("原消息不存在或无权查看");
        visible(user, "peer", id); fillPeers(List.of(m)); return m;
    }
    @Override
    public GroupMessage locateGroup(Long user, Long group, Long id) {
        User member = users.selectById(user);
        if (member == null || !group.equals(member.getGroupId())) throw new BusinessException("您不是该群成员");
        GroupMessage m = groups.selectById(id);
        if (m == null || !group.equals(m.getGroupId())) throw new BusinessException("原消息不存在或无权查看");
        visible(user, "group", id); fillGroups(List.of(m)); return m;
    }
    /**
     * 所有私聊入库后都经认证 WS 推给接收者，引用元数据随正文一起下发。
     * 只推引用消息会漏掉 PC 管理端发来的普通私聊；客户端按 fp 判重，重复到达不会重复入库。
     */
    @Override
    public void notifyPeer(ChatMessage message) {
        Long id = message.getId();
        Long receiver = message.getReceiverId();
        if (id == null || receiver == null) return;
        TransactionUtils.afterCommit(() -> {
            try {
                ChatMessage fresh = peers.selectById(id);
                if (fresh == null || fresh.getRecalledAt() != null) return;
                fillPeers(List.of(fresh));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", fresh.getId()); data.put("senderId", fresh.getSenderId()); data.put("receiverId", fresh.getReceiverId());
                data.put("fp", fresh.getFp()); data.put("content", fresh.getContent()); data.put("msgType", fresh.getMsgType());
                data.put("createdAt", fresh.getCreatedAt().toString()); data.put("quote", fresh.getQuote()); data.put("quoteMessageId", fresh.getQuoteMessageId());
                sockets.pushToUser(receiver, Map.of("type", "message_quote", "data", data));
            } catch (Exception e) {
                // 推送失败不回滚已提交消息，接收方可通过历史同步恢复。
                log.warn("引用消息推送失败: messageId={}, reason={}", id, e.getMessage());
            }
        });
    }
}
