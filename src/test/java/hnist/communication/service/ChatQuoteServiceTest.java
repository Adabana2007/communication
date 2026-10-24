package hnist.communication.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.*;
import hnist.communication.mapper.*;
import hnist.communication.vo.MessageQuoteVO;
import hnist.communication.websocket.LocationWebSocketHandler;
import hnist.communication.service.impl.ChatQuoteServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatQuoteServiceTest {
    ChatMessageMapper peers = mock(ChatMessageMapper.class);
    GroupMessageMapper groups = mock(GroupMessageMapper.class);
    UserMapper users = mock(UserMapper.class);
    ChatMessageActionMapper actions = mock(ChatMessageActionMapper.class);
    LocationWebSocketHandler sockets = mock(LocationWebSocketHandler.class);
    ChatQuoteService service = new ChatQuoteServiceImpl(peers, groups, users, actions, sockets);
    ChatMessage source = ChatMessage.builder().id(10L).senderId(1L).receiverId(2L).content("原消息内容")
        .fp("original").msgType("text").createdAt(LocalDateTime.now()).build();
    ChatMessage reply = ChatMessage.builder().id(20L).senderId(2L).receiverId(1L).content("回复")
        .msgType("text").quoteMessageId(10L).createdAt(LocalDateTime.now()).build();
    @BeforeEach void setup() {
        when(peers.selectOne(any())).thenReturn(source);
        when(peers.selectById(10L)).thenReturn(source);
        when(peers.selectByIds(anyCollection())).thenReturn(List.of(source));
        User sender = new User(); sender.setUserId(1L); sender.setUsername("account"); sender.setNickname("原作者");
        when(users.selectByIds(anyCollection())).thenReturn(List.of(sender));
    }
    @AfterEach void cleanup() { if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization(); }
    @Test void peerQuoteAcceptsBothDirectionsButRejectsOtherConversation() {
        service.validatePeer(2L, 1L, 10L);
        assertThrows(BusinessException.class, () -> service.validatePeer(2L, 3L, 10L));
    }
    @Test void missingInvalidRecalledAndUnsupportedSourceAreRejected() {
        assertThrows(BusinessException.class, () -> service.validatePeer(2L, 1L, 0L));
        source.setRecalledAt(LocalDateTime.now());
        assertThrows(BusinessException.class, () -> service.validatePeer(2L, 1L, 10L));
        source.setRecalledAt(null); source.setMsgType("call");
        assertThrows(BusinessException.class, () -> service.validatePeer(2L, 1L, 10L));
        when(peers.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.validatePeer(2L, 1L, 10L));
    }
    @Test void requesterDeletedOriginalCannotQuoteOrLocateIt() {
        when(actions.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.validatePeer(2L, 1L, 10L));
        assertThrows(BusinessException.class, () -> service.locatePeer(2L, 1L, 10L));
    }
    @Test void audioPeerSourceCanBeQuotedAndRecalledWithoutLeakingItsUrl() {
        source.setMsgType("audio"); source.setContent("/api/chat/media/voice.m4a?d=12");
        assertDoesNotThrow(() -> service.validatePeer(1L, 2L, 10L));
        assertDoesNotThrow(() -> service.validatePeer(2L, 1L, 10L));
        service.fillPeers(List.of(reply));
        assertEquals("audio", reply.getQuote().getMsgType());
        assertEquals(source.getContent(), reply.getQuote().getContent());
        source.setRecalledAt(LocalDateTime.now());
        assertThrows(BusinessException.class, () -> service.validatePeer(2L, 1L, 10L));
        service.fillPeers(List.of(reply));
        assertEquals("recalled", reply.getQuote().getStatus()); assertEquals("", reply.getQuote().getContent());
    }
    @Test void audioGroupSourceCanBeQuotedOnlyInItsOwnGroupAndRecallRedactsIt() {
        GroupMessage original = GroupMessage.builder().id(10L).groupId(7L).senderId(1L).senderName("语音作者")
            .content("/api/chat/media/voice.m4a?d=12").msgType("audio").build();
        GroupMessage answer = GroupMessage.builder().id(20L).groupId(7L).quoteMessageId(10L).build();
        when(groups.selectOne(any())).thenReturn(original); when(groups.selectByIds(anyCollection())).thenReturn(List.of(original));
        assertDoesNotThrow(() -> service.validateGroup(2L, 7L, 10L));
        assertThrows(BusinessException.class, () -> service.validateGroup(2L, 8L, 10L));
        service.fillGroups(List.of(answer)); assertEquals("audio", answer.getQuote().getMsgType());
        original.setRecalledAt(LocalDateTime.now()); service.fillGroups(List.of(answer));
        assertEquals("recalled", answer.getQuote().getStatus()); assertEquals("", answer.getQuote().getContent());
        assertThrows(BusinessException.class, () -> service.validateGroup(2L, 7L, 10L));
    }
    @Test void viewComesFromOriginalAndNeverNestsItsOwnQuote() {
        source.setQuoteMessageId(9L);
        source.setQuote(MessageQuoteVO.builder().content("不能嵌套").build());
        service.fillPeers(List.of(reply));
        assertEquals("原消息内容", reply.getQuote().getContent());
        assertEquals("原作者", reply.getQuote().getSenderName());
        assertEquals(10L, reply.getQuote().getMessageId());
        verify(peers, times(1)).selectByIds(Set.of(10L));
    }
    @Test void recallRedactsImageUrlInSubsequentHistoryWithoutSnapshotUpdates() {
        source.setMsgType("image"); source.setContent("/api/chat/media/secret");
        service.fillPeers(List.of(reply)); assertEquals("/api/chat/media/secret", reply.getQuote().getContent());
        source.setRecalledAt(LocalDateTime.now());
        service.fillPeers(List.of(reply)); assertEquals("recalled", reply.getQuote().getStatus()); assertEquals("", reply.getQuote().getContent());
    }
    @Test void missingOrCrossConversationSourceReturnsEmptyUnavailableCard() {
        when(peers.selectByIds(anyCollection())).thenReturn(List.of());
        service.fillPeers(List.of(reply)); assertEquals("unavailable", reply.getQuote().getStatus());
        when(peers.selectByIds(anyCollection())).thenReturn(List.of(source)); source.setReceiverId(9L);
        service.fillPeers(List.of(reply)); assertEquals("", reply.getQuote().getContent());
    }
    @Test void recalledReplyItselfDoesNotRetainAQuote() {
        reply.setRecalledAt(LocalDateTime.now()); reply.setQuote(MessageQuoteVO.builder().content("旧快照").build());
        service.fillPeers(List.of(reply)); assertNull(reply.getQuote());
    }
    @Test void groupReferenceCannotCrossGroupsAndLocateRequiresMembership() {
        GroupMessage m = GroupMessage.builder().id(10L).groupId(7L).senderId(1L).senderName("作者").content("图片").msgType("image").build();
        when(groups.selectOne(any())).thenReturn(m); when(groups.selectById(10L)).thenReturn(m);
        service.validateGroup(2L, 7L, 10L);
        assertThrows(BusinessException.class, () -> service.validateGroup(2L, 8L, 10L));
        User member = new User(); member.setGroupId(8L); when(users.selectById(2L)).thenReturn(member);
        assertThrows(BusinessException.class, () -> service.locateGroup(2L, 7L, 10L));
        member.setGroupId(7L); assertEquals(m, service.locateGroup(2L, 7L, 10L));
    }
    @Test void groupHistoryRecallAlsoRedactsMedia() {
        GroupMessage original = GroupMessage.builder().id(10L).groupId(7L).content("/video").msgType("video").recalledAt(LocalDateTime.now()).build();
        GroupMessage answer = GroupMessage.builder().id(20L).groupId(7L).quoteMessageId(10L).build();
        when(groups.selectByIds(anyCollection())).thenReturn(List.of(original));
        service.fillGroups(List.of(answer)); assertEquals("recalled", answer.getQuote().getStatus()); assertEquals("", answer.getQuote().getContent());
    }
    @Test void noQuoteDoesNotQuerySourcesAndForgedViewIsCleared() {
        reply.setQuoteMessageId(null); reply.setQuote(MessageQuoteVO.builder().content("伪造").build());
        service.fillPeers(List.of(reply)); assertNull(reply.getQuote()); verify(peers, never()).selectByIds(anyCollection());
    }
    @Test void clientJsonCannotSupplyQuoteSnapshot() throws Exception {
        ChatMessage value = new ObjectMapper().readValue("{\"quoteMessageId\":10,\"quote\":{\"senderName\":\"假作者\",\"content\":\"假内容\"}}", ChatMessage.class);
        assertEquals(10L, value.getQuoteMessageId()); assertNull(value.getQuote());
    }
    @Test void quoteBroadcastWaitsForCommitAndUsesFreshRecalledView() {
        when(peers.selectById(20L)).thenReturn(reply);
        TransactionSynchronizationManager.initSynchronization();
        service.notifyPeer(reply); verifyNoInteractions(sockets);
        source.setRecalledAt(LocalDateTime.now());
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        verify(sockets).pushToUser(eq(1L), argThat(payload -> {
            Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) payload).get("data");
            MessageQuoteVO q = (MessageQuoteVO) data.get("quote");
            return "recalled".equals(q.getStatus()) && "".equals(q.getContent());
        }));
    }
    @Test void peerRetryFoundAfterOriginalLockDoesNotInsertTwice() {
        ChatQuoteService quotes = mock(ChatQuoteService.class);
        when(peers.selectOne(any())).thenReturn(null, reply);
        var service = new hnist.communication.service.impl.ChatMessageServiceImpl(peers, quotes);
        ChatMessage request = ChatMessage.builder().senderId(2L).receiverId(1L).content("回复").msgType("text").fp("retry").quoteMessageId(10L).build();
        assertSame(reply, service.create(request));
        verify(quotes).validatePeer(2L, 1L, 10L); verify(peers, never()).insert(any(ChatMessage.class));
    }
    @Test void groupRetryFoundAfterOriginalLockDoesNotInsertOrBroadcastTwice() {
        ChatQuoteService quotes = mock(ChatQuoteService.class);
        GroupMapper groupMapper = mock(GroupMapper.class);
        User member = new User(); member.setUserId(2L); member.setGroupId(7L);
        when(users.selectById(2L)).thenReturn(member);
        Group group = new Group(); group.setGroupId(7L); when(groupMapper.selectById(7L)).thenReturn(group);
        GroupMessage saved = GroupMessage.builder().id(20L).groupId(7L).senderId(2L).content("回复").quoteMessageId(10L).build();
        when(groups.selectOne(any())).thenReturn(null, saved);
        var service = new hnist.communication.service.impl.GroupMessageServiceImpl(groups, groupMapper, users, sockets, quotes);
        var req = new hnist.communication.dto.GroupMessageRequest(); req.setSenderId(2L); req.setGroupId(7L); req.setContent("回复");req.setFp("retry");req.setQuoteMessageId(10L);
        assertEquals(20L, service.send(req).getId());
        verify(quotes).validateGroup(2L, 7L, 10L); verify(groups, never()).insert(any(GroupMessage.class)); verifyNoInteractions(sockets);
    }

}
