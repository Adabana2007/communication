package hnist.communication.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.entity.ChatMessage;

import java.time.LocalDateTime;
import java.util.List;

/** 单聊消息存取与离线未读补齐查询。 */
public interface ChatMessageService {

    ChatMessage create(ChatMessage message);

    /** 按时间正序分页返回两人之间的聊天记录。 */
    Page<ChatMessage> listMessages(Long userId1, Long userId2, int page, int size);

    /**
     * 查询发给 receiverId 的消息（createdAt >= since，取最近 limit 条），用于离线补未读。
     * 只按会话查询会漏掉离线期间"从没聊过的人"发来的消息，因此必须有按接收者的入口。
     */
    List<ChatMessage> listReceived(Long receiverId, LocalDateTime since, int limit);
}
