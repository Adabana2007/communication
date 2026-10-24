package hnist.communication.service;

import hnist.communication.entity.ChatMessage;
import hnist.communication.entity.GroupMessage;

import java.util.List;

/** 消息引用校验、批量回填与推送服务。 */
public interface ChatQuoteService {

    /** 校验单聊引用消息的归属、可见性与可引用状态。 */
    void validatePeer(Long user, Long peer, Long id);

    /** 校验群聊引用消息的归属、可见性与可引用状态。 */
    void validateGroup(Long user, Long group, Long id);

    /** 为一批单聊消息回填引用快照。 */
    void fillPeers(List<ChatMessage> rows);

    /** 为一批群聊消息回填引用快照。 */
    void fillGroups(List<GroupMessage> rows);

    /** 定位并校验当前用户可查看的单聊消息。 */
    ChatMessage locatePeer(Long user, Long peer, Long id);

    /** 定位并校验当前用户可查看的群聊消息。 */
    GroupMessage locateGroup(Long user, Long group, Long id);

    /** 在事务提交后向接收者推送单聊消息。 */
    void notifyPeer(ChatMessage message);
}
