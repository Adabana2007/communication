package hnist.communication.service;

import hnist.communication.exception.BusinessException;
import hnist.communication.entity.TrajectorySession;
import hnist.communication.mapper.*;
import hnist.communication.service.impl.LocationServiceImpl;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrajectorySessionPersistenceTest {
    TrajectorySessionMapper sessions;
    LocationHistoryMapper history;
    LocationServiceImpl service;
    TrajectorySession session;
    @BeforeEach void setup() {
        sessions = mock(TrajectorySessionMapper.class); history = mock(LocationHistoryMapper.class);
        service = new LocationServiceImpl(mock(UserLocationMapper.class), history, sessions,
                mock(GroupMemberMapper.class), mock(UserMapper.class));
        session = TrajectorySession.builder().sessionId("sess_retry_123").userId(1L).status(0)
                .startTime(LocalDateTime.of(2026, 10, 16, 8, 0)).build();
    }
    @Test void lostStartResponseCanBeRetriedWithoutSecondInsert() {
        when(sessions.selectOne(any())).thenReturn(session);
        assertSame(session, service.startSession(1L, session.getSessionId(), 1L));
        verify(sessions, never()).insert(any(TrajectorySession.class));
    }
    @Test void retryCannotTakeOverAnotherUsersSession() {
        when(sessions.selectOne(any())).thenReturn(session);
        assertThrows(BusinessException.class, () -> service.startSession(2L, session.getSessionId(), 1L));
    }
    @Test void concurrentDuplicateStartReturnsCommittedWinner() {
        when(sessions.selectOne(any())).thenReturn(null, session);
        when(sessions.insert(any(TrajectorySession.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
        assertSame(session, service.startSession(1L, session.getSessionId(), 1L));
    }
    @Test void lostFinishResponseCanBeRetriedWithoutRecounting() {
        session.setStatus(1); when(sessions.selectOne(any())).thenReturn(session);
        assertSame(session, service.finishSession(session.getSessionId(), null, 60));
        verifyNoInteractions(history); verify(sessions, never()).updateById(any(TrajectorySession.class));
    }
    @Test void zeroSecondRecordingRetainsClientDuration() {
        when(sessions.selectOne(any())).thenReturn(session); when(history.selectList(any())).thenReturn(List.of());
        var result = service.finishSession(session.getSessionId(), null, 0);
        assertEquals(0, result.getDurationSec()); assertEquals(1, result.getStatus()); assertEquals(0, result.getPointCount());
    }
    @Test void lostPointResponseDoesNotInsertSameCollectedPointAgain() {
        when(history.selectCount(any())).thenReturn(1L);
        service.updateLocationWithSession(1L, 29.0, 113.0, null, 0.0, 0.0, 0.0, "sess_retry_123", 1792112400123L);
        verify(history, never()).insert(any(hnist.communication.entity.LocationHistory.class));
    }
    @Test void firstCollectedPointIsInsertedAtDatabaseTimestampPrecision() {
        when(history.selectCount(any())).thenReturn(0L);
        service.updateLocationWithSession(1L, 29.0, 113.0, null, 0.0, 0.0, 0.0, "sess_retry_123", 1792112400123L);
        var captured = org.mockito.ArgumentCaptor.forClass(hnist.communication.entity.LocationHistory.class);
        verify(history).insert(captured.capture()); assertEquals(0, captured.getValue().getRecordedAt().getNano());
    }
}
