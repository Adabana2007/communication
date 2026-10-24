package hnist.communication.service;

import hnist.communication.entity.ChatMessageAction;

import java.util.List;

/** 聊天消息删除/撤回动作服务。 */
public interface ChatActionService {

    /** 为缺少指纹的历史消息解析唯一消息 ID。 */
    Long resolveLegacy(Long userId, String type, Long conversation, Long sender, Long timestamp, String content);

    /** 按幂等指纹解析当前会话中的唯一消息 ID。 */
    Long resolve(Long userId, String type, Long conversation, String fp);

    /** 增量同步指定用户的消息删除/撤回事件。 */
    List<ChatMessageAction> sync(Long userId, long after);

    /** 删除或撤回消息并返回幂等事件。 */
    ChatMessageAction operate(Long userId, String type, Long id, String action);
}
