package hnist.communication.websocket;

import hnist.communication.service.LocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 实际 handler 单元测试；仅 WS/定位存储为替身，不启动数据库或真实服务器。 */
class LocationPresenceTest {
    private LocationService location;
    private TestHandler handler;

    private static class TestHandler extends LocationWebSocketHandler {
        long time;
        TestHandler(LocationService service) { super(service); }
        @Override long presenceNowNanos() { return time; }
        void seconds(long seconds) { time = TimeUnit.SECONDS.toNanos(seconds); }
        void receive(WebSocketSession session, String json) {
            handleTextMessage(session, new TextMessage(json));
        }
        void pong(WebSocketSession session, PingMessage ping) {
            handlePongMessage(session, new PongMessage(ping.getPayload().asReadOnlyBuffer()));
        }
    }

    private WebSocketSession session(long group, long user) throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicBoolean open = new AtomicBoolean(true);
        when(session.getAttributes()).thenReturn(Map.of("groupId", group, "userId", user, "username", "test"));
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(invocation -> { open.set(false); return null; }).when(session).close(any(CloseStatus.class));
        return session;
    }

    private WebSocketSession presenceSession(long group, long user) throws IOException {
        WebSocketSession session = session(group, user);
        when(session.getAttributes()).thenReturn(Map.of("groupId", group, "userId", user,
                "username", "pc", "presenceOnly", true));
        return session;
    }

    private PingMessage latestPing(WebSocketSession session) throws IOException {
        ArgumentCaptor<PingMessage> captor = ArgumentCaptor.forClass(PingMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        return captor.getValue();
    }

    @BeforeEach void setup() {
        location = mock(LocationService.class);
        handler = new TestHandler(location);
    }

    @Test void authenticatedConnectionIsOnlineImmediatelyWithoutGps() throws Exception {
        handler.afterConnectionEstablished(session(1, 7));
        assertEquals(Set.of(7L), handler.getOnlineUserIds());
        assertEquals(Set.of(7L), handler.getOnlineUsers(1L));
        verifyNoInteractions(location);
    }

    @Test void backgroundWithValidHeartbeatsRemainsOnlineForHours() throws Exception {
        WebSocketSession session = session(1, 7);
        handler.afterConnectionEstablished(session);
        for (int seconds = 30; seconds <= 10800; seconds += 30) {
            handler.seconds(seconds);
            handler.receive(session, "{\"type\":\"ping\"}");
            handler.expireInactiveSessions();
            assertEquals(Set.of(7L), handler.getOnlineUserIds());
        }
        verify(session, never()).close(any(CloseStatus.class));
        verifyNoInteractions(location);
    }

    @Test void silentDropIsOnlineBeforeDeadlineAndOfflineAt120Seconds() throws Exception {
        WebSocketSession session = session(1, 7);
        handler.afterConnectionEstablished(session);
        handler.seconds(119);
        assertEquals(Set.of(7L), handler.getOnlineUserIds());
        handler.seconds(120);
        assertTrue(handler.getOnlineUserIds().isEmpty());
        verify(session).close(CloseStatus.GOING_AWAY);
    }

    @Test void scheduledSweepWorksWithoutAnyPresenceQueries() throws Exception {
        WebSocketSession session = session(1, 7);
        handler.afterConnectionEstablished(session);
        handler.seconds(125);
        handler.expireInactiveSessions();
        verify(session).close(CloseStatus.GOING_AWAY);
        assertTrue(handler.getOnlineUsers(1L).isEmpty());
    }

    @Test void pongReplyAndRenewalAreIndependentOfLocationPermissions() throws Exception {
        WebSocketSession session = session(1, 7);
        handler.afterConnectionEstablished(session);
        handler.seconds(110);
        handler.receive(session, "{\"type\":\"ping\"}");
        handler.seconds(220);
        assertEquals(Set.of(7L), handler.getOnlineUserIds());
        verify(session).sendMessage(any(TextMessage.class));
        verifyNoInteractions(location);
    }

    @Test void explicitCloseIsOfflineImmediately() throws Exception {
        WebSocketSession session = session(1, 7);
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertTrue(handler.getOnlineUserIds().isEmpty());
    }

    @Test void closedSocketWithoutCloseCallbackIsNotReportedOnline() throws Exception {
        WebSocketSession session = session(1, 7);
        handler.afterConnectionEstablished(session);
        when(session.isOpen()).thenReturn(false);
        assertTrue(handler.getOnlineUserIds().isEmpty());
    }

    @Test void transportErrorIsOfflineWithoutWaitingForCloseEvent() throws Exception {
        WebSocketSession session = session(1, 7);
        handler.afterConnectionEstablished(session);
        handler.handleTransportError(session, new IOException("test network lost"));
        assertTrue(handler.getOnlineUserIds().isEmpty());
    }

    @Test void returningAfterOneDayIsOnlineImmediatelyNotAfterAnotherTimeout() throws Exception {
        WebSocketSession old = session(1, 7);
        handler.afterConnectionEstablished(old);
        handler.seconds(86400);
        handler.expireInactiveSessions();
        assertTrue(handler.getOnlineUserIds().isEmpty());
        WebSocketSession fresh = session(1, 7);
        handler.afterConnectionEstablished(fresh);
        assertEquals(Set.of(7L), handler.getOnlineUserIds());
        handler.receive(fresh, "{\"type\":\"ping\"}");
        assertEquals(Set.of(7L), handler.getOnlineUsers(1L));
    }

    @Test void delayedOldCloseCannotRemoveReconnectedUser() throws Exception {
        WebSocketSession old = session(1, 7), fresh = session(1, 7);
        handler.afterConnectionEstablished(old);
        handler.seconds(10);
        handler.afterConnectionEstablished(fresh);
        handler.afterConnectionClosed(old, CloseStatus.NORMAL);
        assertEquals(Set.of(7L), handler.getOnlineUserIds());
        verify(fresh, never()).close(any(CloseStatus.class));
    }

    @Test void oldPingCannotRenewReplacementConnection() throws Exception {
        WebSocketSession old = session(1, 7), fresh = session(1, 7);
        handler.afterConnectionEstablished(old);
        handler.afterConnectionEstablished(fresh);
        clearInvocations(old);
        handler.seconds(119);
        handler.receive(old, "{\"type\":\"ping\"}");
        verify(old, never()).sendMessage(any(TextMessage.class));
        handler.seconds(120);
        assertTrue(handler.getOnlineUserIds().isEmpty());
    }

    @Test void expiredPingCannotResurrectRemovedConnection() throws Exception {
        WebSocketSession old = session(1, 7);
        handler.afterConnectionEstablished(old);
        handler.seconds(120);
        handler.expireInactiveSessions();
        clearInvocations(old);
        handler.receive(old, "{\"type\":\"ping\"}");
        assertTrue(handler.getOnlineUserIds().isEmpty());
        verify(old, never()).sendMessage(any(TextMessage.class));
    }

    @Test void malformedAndUnknownMessagesDoNotRenewPresence() throws Exception {
        WebSocketSession session = session(1, 7);
        handler.afterConnectionEstablished(session);
        handler.seconds(119);
        handler.receive(session, "bad json");
        handler.receive(session, "{\"type\":\"unknown\"}");
        handler.receive(session, "{\"type\":\"location\"}");
        handler.seconds(120);
        assertTrue(handler.getOnlineUserIds().isEmpty());
    }

    @Test void validLocationAndVoiceKeepExistingBroadcastBehaviour() throws Exception {
        WebSocketSession sender = session(1, 7), receiver = session(1, 8);
        handler.afterConnectionEstablished(sender);
        handler.afterConnectionEstablished(receiver);
        handler.seconds(110);
        handler.receive(sender, "{\"type\":\"location\",\"data\":{\"latitude\":26,\"longitude\":112,\"visible\":false}}");
        handler.receive(receiver, "{\"type\":\"voice_broadcast\",\"data\":{\"msgId\":\"test\"}}");
        handler.seconds(220);
        assertEquals(Set.of(7L, 8L), handler.getOnlineUserIds());
        verify(location).updateLocation(7L, 26.0, 112.0, null, 0.0, 0.0, 0.0);
        verify(receiver, times(2)).sendMessage(any(TextMessage.class));
    }

    @Test void sendFailureClosesSessionAndDoesNotPreventOtherPeersReceiving() throws Exception {
        WebSocketSession broken = session(1, 7), healthy = session(1, 8);
        handler.afterConnectionEstablished(broken);
        handler.afterConnectionEstablished(healthy);
        doThrow(new IOException("test send failed")).when(broken).sendMessage(any(TextMessage.class));
        handler.pushToGroup(1L, Map.of("type", "text_broadcast", "data", Map.of()));
        assertEquals(Set.of(8L), handler.getOnlineUserIds());
        verify(healthy).sendMessage(any(TextMessage.class));
        verify(broken).close(CloseStatus.GOING_AWAY);
    }

    @Test void failedPongReplyCannotLeaveFalseOnlineSession() throws Exception {
        WebSocketSession session = session(1, 7);
        handler.afterConnectionEstablished(session);
        doThrow(new IOException("test send failed")).when(session).sendMessage(any(TextMessage.class));
        handler.receive(session, "{\"type\":\"ping\"}");
        assertTrue(handler.getOnlineUserIds().isEmpty());
    }

    @Test void logoutClosesAllGroupsForUserButPreservesOtherUsers() throws Exception {
        handler.afterConnectionEstablished(session(1, 7));
        handler.afterConnectionEstablished(session(2, 7));
        handler.afterConnectionEstablished(session(2, 8));
        assertEquals(2, handler.closeUserSessions(7L));
        assertEquals(Set.of(8L), handler.getOnlineUserIds());
        assertEquals(0, handler.closeUserSessions(7L));
        assertEquals(0, handler.closeUserSessions(null));
    }

    @Test void globalAndGroupQueriesUseSameLeaseAndDoNotAffectOtherGroups() throws Exception {
        handler.afterConnectionEstablished(session(1, 7));
        handler.seconds(60);
        handler.afterConnectionEstablished(session(2, 8));
        handler.seconds(120);
        assertTrue(handler.getOnlineUsers(1L).isEmpty());
        assertEquals(Set.of(8L), handler.getOnlineUserIds());
        assertEquals(Set.of(8L), handler.getOnlineUsers(2L));
    }

    @Test void concurrentOldCloseAndRegistrationNeverDeleteNewRoom() throws Exception {
        for (int i = 0; i < 100; i++) {
            TestHandler h = new TestHandler(location);
            WebSocketSession old = session(1, 7), fresh = session(1, 7);
            h.afterConnectionEstablished(old);
            CompletableFuture<Void> registration = CompletableFuture.runAsync(() -> h.afterConnectionEstablished(fresh));
            CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> h.afterConnectionClosed(old, CloseStatus.NORMAL));
            CompletableFuture.allOf(registration, closing).get(5, TimeUnit.SECONDS);
            assertEquals(Set.of(7L), h.getOnlineUserIds(), "iteration=" + i);
        }
    }

    @Test void standardPongWithoutGpsOrJsonHeartbeatKeepsPcOnlineForHours() throws Exception {
        WebSocketSession pc = session(1, 7);
        handler.afterConnectionEstablished(pc);
        for (int seconds = 30; seconds <= 10800; seconds += 30) {
            handler.seconds(seconds);
            handler.probeConnectedSessions();
            handler.pong(pc, latestPing(pc));
            handler.expireInactiveSessions();
            assertEquals(Set.of(7L), handler.getOnlineUserIds());
        }
        verify(pc, never()).close(any(CloseStatus.class));
        verifyNoInteractions(location);
    }

    @Test void sendingServerPingDoesNotKeepUnresponsivePcOnline() throws Exception {
        WebSocketSession pc = session(1, 7);
        handler.afterConnectionEstablished(pc);
        handler.seconds(110);
        handler.probeConnectedSessions();
        verify(pc).sendMessage(any(PingMessage.class));
        handler.seconds(120);
        handler.expireInactiveSessions();
        assertTrue(handler.getOnlineUserIds().isEmpty());
        verify(pc).close(CloseStatus.GOING_AWAY);
    }

    @Test void unsolicitedAndMismatchedPongCannotRenewPresence() throws Exception {
        WebSocketSession pc = session(1, 7);
        handler.afterConnectionEstablished(pc);
        handler.seconds(110);
        handler.handlePongMessage(pc, new PongMessage(ByteBuffer.wrap(new byte[]{1})));
        handler.probeConnectedSessions();
        handler.handlePongMessage(pc, new PongMessage(ByteBuffer.wrap(new byte[]{2})));
        handler.seconds(120);
        assertTrue(handler.getOnlineUserIds().isEmpty());
    }

    @Test void priorProbePongCannotRenewNewProbe() throws Exception {
        WebSocketSession pc = session(1, 7);
        handler.afterConnectionEstablished(pc);
        handler.seconds(30);
        handler.probeConnectedSessions();
        PingMessage previous = latestPing(pc);
        handler.seconds(110);
        handler.probeConnectedSessions();
        handler.pong(pc, previous);
        handler.seconds(120);
        assertTrue(handler.getOnlineUserIds().isEmpty());
    }

    @Test void retiredSessionPongCannotRenewReplacementOrResurrectItself() throws Exception {
        WebSocketSession old = session(1, 7), fresh = session(1, 7);
        handler.afterConnectionEstablished(old);
        handler.probeConnectedSessions();
        PingMessage oldPing = latestPing(old);
        handler.afterConnectionEstablished(fresh);
        handler.seconds(110);
        handler.pong(old, oldPing);
        handler.seconds(120);
        assertTrue(handler.getOnlineUserIds().isEmpty());
    }

    @Test void failedControlPingImmediatelyRemovesPc() throws Exception {
        WebSocketSession pc = session(1, 7);
        handler.afterConnectionEstablished(pc);
        doThrow(new IOException("probe failed")).when(pc).sendMessage(any(PingMessage.class));
        handler.probeConnectedSessions();
        assertTrue(handler.getOnlineUserIds().isEmpty());
        verify(pc).close(CloseStatus.GOING_AWAY);
    }

    @Test void presenceOnlyIsOnlineInActualGroupButNeverReceivesOrSendsRoomMessages() throws Exception {
        WebSocketSession pc = presenceSession(1, 7), app = session(1, 8);
        handler.afterConnectionEstablished(pc);
        handler.afterConnectionEstablished(app);
        assertEquals(Set.of(7L, 8L), handler.getOnlineUserIds());
        assertEquals(Set.of(7L, 8L), handler.getOnlineUsers(1L));
        assertTrue(handler.getOnlineUsers(999L).isEmpty());
        handler.receive(pc, "{\"type\":\"location\",\"data\":{\"latitude\":26,\"longitude\":112}}");
        handler.receive(pc, "{\"type\":\"voice_broadcast\",\"data\":{}}");
        verifyNoInteractions(location);
        verify(app, never()).sendMessage(any());
        handler.pushToGroup(1L, Map.of("type", "text_broadcast", "data", Map.of()));
        verify(app).sendMessage(any(TextMessage.class));
        verify(pc, never()).sendMessage(any(TextMessage.class));
        handler.receive(pc, "{\"type\":\"ping\"}");
        verify(pc).sendMessage(any(TextMessage.class));
    }

    @Test void presenceOnlyDoesNotReplaceExistingAuthorizedRoomConnection() throws Exception {
        WebSocketSession pc = presenceSession(1, 7), app = session(1, 7);
        handler.afterConnectionEstablished(app);
        handler.afterConnectionEstablished(pc);
        handler.pushToGroup(1L, Map.of("type", "text_broadcast", "data", Map.of()));
        verify(app).sendMessage(any(TextMessage.class));
        verify(app, never()).close(any(CloseStatus.class));
        assertEquals(Set.of(7L), handler.getOnlineUserIds());
    }

    @Test void presenceOnlySupportsStandardHeartbeatAndExpiresWithoutResponse() throws Exception {
        WebSocketSession pc = presenceSession(1, 7);
        handler.afterConnectionEstablished(pc);
        handler.seconds(110);
        handler.probeConnectedSessions();
        handler.pong(pc, latestPing(pc));
        handler.seconds(220);
        assertEquals(Set.of(7L), handler.getOnlineUserIds());
        handler.seconds(230);
        handler.expireInactiveSessions();
        assertTrue(handler.getOnlineUserIds().isEmpty());
    }

    @Test void latePresenceOnlyCloseCannotRemoveNewRegistration() throws Exception {
        WebSocketSession old = presenceSession(1, 7), fresh = presenceSession(1, 7);
        handler.afterConnectionEstablished(old);
        handler.afterConnectionEstablished(fresh);
        handler.afterConnectionClosed(old, CloseStatus.NORMAL);
        assertEquals(Set.of(7L), handler.getOnlineUserIds());
    }

    @Test void logoutClosesPresenceOnlyAndAuthorizedConnectionsTogether() throws Exception {
        handler.afterConnectionEstablished(presenceSession(1, 7));
        handler.afterConnectionEstablished(session(1, 7));
        handler.afterConnectionEstablished(session(1, 8));
        assertEquals(2, handler.closeUserSessions(7L));
        assertEquals(Set.of(8L), handler.getOnlineUserIds());
    }

    @Test void concurrentControlPingJsonPongAndBroadcastAreSerialized() throws Exception {
        WebSocketSession pc = session(1, 7);
        handler.afterConnectionEstablished(pc);
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
        doAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            try { Thread.sleep(20); } finally { active.decrementAndGet(); }
            return null;
        }).when(pc).sendMessage(any());
        CountDownLatch start = new CountDownLatch(1);
        Runnable gate = () -> { try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); } };
        CompletableFuture<Void> ping = CompletableFuture.runAsync(() -> { gate.run(); handler.probeConnectedSessions(); });
        CompletableFuture<Void> json = CompletableFuture.runAsync(() -> { gate.run(); handler.receive(pc, "{\"type\":\"ping\"}"); });
        CompletableFuture<Void> broadcast = CompletableFuture.runAsync(() -> { gate.run(); handler.pushToGroup(1L, Map.of("type", "text_broadcast")); });
        start.countDown();
        CompletableFuture.allOf(ping, json, broadcast).get(5, TimeUnit.SECONDS);
        assertEquals(1, maximum.get());
        assertEquals(Set.of(7L), handler.getOnlineUserIds());
        verify(pc, never()).close(any(CloseStatus.class));
    }
}
