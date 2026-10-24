package hnist.communication.service;

import hnist.communication.exception.BusinessException;
import hnist.communication.entity.*;
import hnist.communication.mapper.*;
import hnist.communication.websocket.LocationWebSocketHandler;
import hnist.communication.service.impl.ChatActionServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.List;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatActionServiceTest {
    @Mock ChatMessageMapper peers;
    @Mock GroupMessageMapper groups;
    @Mock ChatMessageActionMapper actions;
    @Mock UserMapper users;
    @Mock LocationWebSocketHandler sockets;
    @InjectMocks ChatActionServiceImpl service;

    @BeforeAll static void tableMetadata() {
        // 独立单元测试显式初始化ORM元数据，不依赖连接数据库的Spring启动测试。
        for (Class<?> type : List.of(ChatMessage.class, GroupMessage.class, ChatMessageAction.class, User.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "unit-test"), type);
    }

    @BeforeEach void transaction() { TransactionSynchronizationManager.initSynchronization(); }
    @AfterEach void cleanup() { TransactionSynchronizationManager.clearSynchronization(); }
    private ChatMessage message() {
        return ChatMessage.builder().id(10L).senderId(1L).receiverId(2L).content("原文")
            .msgType("text").fp("fingerprint").createdAt(LocalDateTime.now()).build();
    }
    private void actor(long id) { when(users.selectById(id)).thenReturn(User.builder().userId(id).username("用户").groupId(5L).build()); }
    @Test void strangerCannotDelete() {
        actor(3); when(peers.selectById(10L)).thenReturn(message());
        assertThrows(BusinessException.class, () -> service.operate(3L, "peer", 10L, "delete"));
        verifyNoInteractions(actions); verifyNoInteractions(sockets);
    }
    @ParameterizedTest @ValueSource(strings = {"text", "audio"}) void recipientCannotRecall(String type) {
        actor(2); ChatMessage m = message(); m.setMsgType(type); when(peers.selectById(10L)).thenReturn(m);
        assertThrows(BusinessException.class, () -> service.operate(2L, "peer", 10L, "recall"));
        verifyNoInteractions(actions);
    }
    @ParameterizedTest @ValueSource(strings = {"text", "audio"}) void recallExpiresOnServer(String type) {
        actor(1); ChatMessage m = message(); m.setCreatedAt(LocalDateTime.now().minusMinutes(3));
        m.setMsgType(type);
        when(peers.selectById(10L)).thenReturn(m);
        assertThrows(BusinessException.class, () -> service.operate(1L, "peer", 10L, "recall"));
        verify(actions, never()).insertOnce(any()); verify(peers, never()).update(isNull(), any());
    }
    @ParameterizedTest @ValueSource(strings = {"text", "audio"}) void deletionOnlyTargetsTheActingAccount(String type) {
        actor(2); ChatMessage m = message(); m.setMsgType(type); when(peers.selectById(10L)).thenReturn(m);
        service.operate(2L, "peer", 10L, "delete");
        ArgumentCaptor<ChatMessageAction> capture = ArgumentCaptor.forClass(ChatMessageAction.class);
        verify(actions).insertOnce(capture.capture());
        assertEquals(2L, capture.getValue().getUserId()); assertEquals(1L, capture.getValue().getPeerId());
        assertEquals("delete", capture.getValue().getAction());
        verify(peers, never()).deleteById(anyLong()); verify(peers, never()).update(isNull(), any());
    }
    @ParameterizedTest @ValueSource(strings = {"text", "audio"}) void recallTargetsBothParticipantsAfterCommit(String type) {
        actor(1); ChatMessage m = message(); m.setMsgType(type); when(peers.selectById(10L)).thenReturn(m);
        when(peers.update(isNull(), any())).thenReturn(1);
        when(actions.insertOnce(any())).thenReturn(1);
        ChatMessageAction saved = new ChatMessageAction();
        saved.setId(9L); saved.setCreatedAt(LocalDateTime.now());
        when(actions.selectOne(any())).thenReturn(null, saved, saved, saved);
        service.operate(1L, "peer", 10L, "recall");
        ArgumentCaptor<ChatMessageAction> capture = ArgumentCaptor.forClass(ChatMessageAction.class);
        verify(actions, times(2)).insertOnce(capture.capture());
        assertEquals(List.of(1L, 2L), capture.getAllValues().stream().map(ChatMessageAction::getUserId).sorted().toList());
        verifyNoInteractions(sockets);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        verify(sockets).pushToUser(eq(1L), any()); verify(sockets).pushToUser(eq(2L), any());
    }
    @Test void nonMemberCannotOperateGroupHistory() {
        actor(1);
        when(groups.selectById(10L)).thenReturn(GroupMessage.builder().id(10L).senderId(1L).groupId(6L).build());
        assertThrows(BusinessException.class, () -> service.operate(1L, "group", 10L, "delete"));
        verifyNoInteractions(actions);
    }
    @Test void audioGroupRecallTargetsMembersAfterCommit() {
        actor(1); when(groups.selectById(10L)).thenReturn(GroupMessage.builder().id(10L).groupId(5L).senderId(1L)
            .msgType("audio").content("/voice.m4a?d=12").createdAt(LocalDateTime.now()).build());
        when(groups.update(isNull(), any())).thenReturn(1); when(actions.insertOnce(any())).thenReturn(1);
        when(users.selectList(any())).thenReturn(List.of(User.builder().userId(1L).build(), User.builder().userId(2L).build()));
        ChatMessageAction saved = new ChatMessageAction(); saved.setId(9L); saved.setCreatedAt(LocalDateTime.now());
        when(actions.selectOne(any())).thenReturn(null, saved, saved, saved);
        service.operate(1L, "group", 10L, "recall");
        ArgumentCaptor<ChatMessageAction> capture = ArgumentCaptor.forClass(ChatMessageAction.class);
        verify(actions, times(2)).insertOnce(capture.capture());
        assertEquals(List.of(1L, 2L), capture.getAllValues().stream().map(ChatMessageAction::getUserId).sorted().toList());
        verifyNoInteractions(sockets); TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        verify(sockets).pushToUser(eq(1L), any()); verify(sockets).pushToUser(eq(2L), any());
    }
    @Test void audioGroupDeletionOnlyHidesItForTheActingMember() {
        actor(2); when(groups.selectById(10L)).thenReturn(GroupMessage.builder().id(10L).groupId(5L).senderId(1L)
            .msgType("audio").content("/voice.m4a?d=12").createdAt(LocalDateTime.now()).build());
        service.operate(2L, "group", 10L, "delete");
        ArgumentCaptor<ChatMessageAction> capture = ArgumentCaptor.forClass(ChatMessageAction.class);
        verify(actions).insertOnce(capture.capture()); assertEquals(2L, capture.getValue().getUserId());
        assertEquals(5L, capture.getValue().getGroupId()); assertEquals("delete", capture.getValue().getAction());
        verify(groups, never()).update(isNull(), any()); verify(groups, never()).deleteById(anyLong());
    }
    @Test void repeatedOperationIsIdempotent() {
        actor(1); when(peers.selectById(10L)).thenReturn(message());
        ChatMessageAction previous = new ChatMessageAction(); when(actions.selectOne(any())).thenReturn(previous);
        assertSame(previous, service.operate(1L, "peer", 10L, "recall"));
        verify(actions, never()).insertOnce(any()); verify(peers, never()).update(isNull(), any());
    }
    @Test void rejectsInvalidOperation() {
        assertThrows(BusinessException.class, () -> service.operate(1L, "peer", 10L, "remove-everyone"));
        verifyNoInteractions(users, peers, groups, actions);
    }
    @Test void changedMessageStateDoesNotEmitRecall() {
        actor(1); when(peers.selectById(10L)).thenReturn(message());
        when(peers.update(isNull(), any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.operate(1L, "peer", 10L, "recall"));
        verify(actions, never()).insertOnce(any()); verifyNoInteractions(sockets);
    }
    @Test void fingerprintResolutionRejectsAmbiguousMatches() {
        when(peers.selectList(any())).thenReturn(List.of(message(), message()));
        assertNull(service.resolve(1L, "peer", 2L, "fingerprint"));
    }
    @Test void fingerprintResolutionReturnsOnlyTheMatchingRow() {
        when(peers.selectList(any())).thenReturn(List.of(message()));
        assertEquals(10L, service.resolve(1L, "peer", 2L, "fingerprint"));
    }
    @Test void groupResolutionRequiresMembership() {
        actor(1);
        assertThrows(BusinessException.class, () -> service.resolve(1L, "group", 999L, "fingerprint"));
        verifyNoInteractions(groups);
    }
    @Test void oldGroupCacheIsResolvedOnlyWhenTheMatchIsUnique() {
        actor(1);
        GroupMessage message = GroupMessage.builder().id(66L).groupId(5L).senderId(2L).content("旧消息").build();
        when(groups.selectList(any())).thenReturn(List.of(message), List.of(message, message));
        long time = System.currentTimeMillis();
        assertEquals(66L, service.resolveLegacy(1L, "group", 5L, 2L, time, "旧消息"));
        assertNull(service.resolveLegacy(1L, "group", 5L, 2L, time, "旧消息"));
    }
    @Test void oldCacheResolutionCannotReadAnotherGroup() {
        actor(1);
        assertThrows(BusinessException.class, () -> service.resolveLegacy(1L, "group", 999L, 2L, System.currentTimeMillis(), "旧消息"));
        verifyNoInteractions(groups);
    }
}
