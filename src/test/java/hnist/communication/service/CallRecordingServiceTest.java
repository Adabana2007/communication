package hnist.communication.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.User;
import hnist.communication.mapper.UserMapper;
import hnist.communication.manager.recording.CallMediaPresence;
import hnist.communication.manager.recording.CallRecordingJob;
import hnist.communication.manager.recording.CallRecordingMedia;
import hnist.communication.manager.recording.CallRecordingProperties;
import hnist.communication.service.impl.CallRecordingServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CallRecordingServiceTest {
    @TempDir Path directory;
    CallRecordingServiceImpl service;
    CallRecordingMedia media;
    CallMediaPresence presence;
    CallRecordingProperties properties;
    UserMapper users;
    java.util.Map<Process, CompletableFuture<Process>> exits = new java.util.concurrent.ConcurrentHashMap<>();
    @BeforeEach void setup() throws Exception {
        properties = new CallRecordingProperties(); properties.setStorage(directory.toString()); properties.setMinFreeBytes(0);
        media = mock(CallRecordingMedia.class); presence = mock(CallMediaPresence.class);
        users = mock(UserMapper.class);
        when(users.selectById(2L)).thenReturn(User.builder().userId(2L).username("peer2").nickname("张三").build());
        when(media.capture(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            ((LongConsumer) invocation.getArgument(3)).accept(1_000_000);
            Process process = mock(Process.class); var exit = new CompletableFuture<Process>();
            exits.put(process, exit); when(process.onExit()).thenReturn(exit); return process;
        });
        when(media.compose(any(), any())).thenAnswer(invocation -> {
            Files.write(((Path)invocation.getArgument(1)).resolve("result.mp4"), new byte[2048]);
            return new CallRecordingMedia.Probe(0, 5.5, true, true, "h264", "aac");
        });
        service = new CallRecordingServiceImpl(properties, media, presence, new ObjectMapper(), users); service.init();
    }
    @AfterEach void close() throws Exception {
        // 新增双方异步收尾用例须等待任务落盘，避免 Windows 清理临时目录时仍有后台写入。
        long deadline = System.currentTimeMillis() + 5000;
        while (java.util.stream.Stream.of(1L, 2L, 3L, 4L).flatMap(owner -> service.list(owner).stream())
            .anyMatch(job -> "processing".equals(job.state())) && System.currentTimeMillis() < deadline) Thread.sleep(10);
        service.close();
        var field = CallRecordingServiceImpl.class.getDeclaredField("workers"); field.setAccessible(true);
        ((java.util.concurrent.ExecutorService) field.get(service)).awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    CallRecordingJob.View start(String request) { return service.start(1, "call_test_123", 2, request, false, true, false); }
    @Test void requestRetriesAreIdempotent() throws Exception {
        var first = start("request_123"); var second = start("request_123");
        assertEquals(first.id(), second.id()); verify(media, times(1)).capture(anyString(), anyString(), any(), any());
        assertThrows(BusinessException.class, () -> service.start(1, "call_other_456", 3, "request_123", false, false, false));
    }
    @Test void recordingAccessIsOwnerScoped() {
        var job = start("request_123");
        assertThrows(BusinessException.class, () -> service.get(2, job.id()));
        assertThrows(BusinessException.class, () -> service.stop(2, job.id()));
        assertThrows(BusinessException.class, () -> service.file(2, job.id()));
        assertTrue(service.list(2).isEmpty());
    }
    @Test void validatesRoomPeerAndRequestBeforeLaunchingProcesses() throws Exception {
        assertThrows(BusinessException.class, () -> service.start(1, "../secret", 2, "request_123", false, false, false));
        assertThrows(BusinessException.class, () -> service.start(1, "call_test_123", 1, "request_123", false, false, false));
        assertThrows(BusinessException.class, () -> service.start(1, "call_test_123", 2, "; bad", false, false, false));
        verifyNoInteractions(media);
    }
    @Test void rejectsMissingStreamsAndCapacityExhaustion() {
        doThrow(new BusinessException("双方媒体尚未就绪")).when(presence).requireStreams(1, "call_test_123", 2);
        assertThrows(BusinessException.class, () -> start("request_123"));
        reset(presence); start("request_456"); properties.setMaxConcurrent(1);
        assertThrows(BusinessException.class, () -> service.start(3, "call_other_789", 4, "request_789", false, false, false));
    }
    @Test void stopIsIdempotentAndProducesPersistedVerifiedDownload() throws Exception {
        var job = start("request_123"); service.stop(1, job.id()); service.stop(1, job.id());
        long deadline = System.currentTimeMillis() + 5000;
        while (!service.get(1, job.id()).state().equals("ready") && System.currentTimeMillis() < deadline) Thread.sleep(20);
        var ready = service.get(1, job.id()); assertEquals("ready", ready.state()); assertEquals(5.5, ready.duration());
        assertEquals(2048, ready.size()); assertEquals(64, ready.sha256().length()); assertEquals(32, ready.md5().length());
        assertEquals(2048, Files.size(service.file(1, job.id()))); verify(media, times(1)).compose(any(), any());
        service.close(); service = new CallRecordingServiceImpl(properties, media, presence, new ObjectMapper(), users); service.init();
        assertEquals("ready", service.get(1, job.id()).state());
    }
    @Test void changingLayoutDoesNotStartAnotherCapture() throws Exception {
        var job = start("request_123"); service.layout(1, job.id(), true, false, true);
        CallRecordingJob stored = new ObjectMapper().readValue(directory.resolve(job.id()).resolve("job.json").toFile(), CallRecordingJob.class);
        assertEquals(2, stored.getLayouts().size()); assertTrue(stored.getLayouts().getLast().swapped());
        service.layout(1, job.id(), true, false, true);
        stored = new ObjectMapper().readValue(directory.resolve(job.id()).resolve("job.json").toFile(), CallRecordingJob.class);
        assertEquals(2, stored.getLayouts().size()); verify(media, times(1)).capture(anyString(), anyString(), any(), any());
    }

    @Test void lateNormalEndCorrectsProcessExitButNotRealMediaFailure() throws Exception {
        var job = start("request_exit");
        var process = exits.keySet().iterator().next(); exits.get(process).complete(process);
        awaitState(job.id(), "processing", "ready");
        assertTrue(service.get(1, job.id()).interrupted());
        var result = service.stop(1, job.id(), "call_end");
        assertFalse(result.interrupted()); assertEquals("call_end", result.endReason());
        assertFalse(service.stop(1, job.id(), "media_error").interrupted(), "late duplicate stops cannot rewrite a known normal end");
        verify(media, timeout(5000).times(1)).compose(any(), any());
    }

    @Test void genuineFailureAndTimeoutRemainInterrupted() throws Exception {
        var job = start("request_error"); service.stop(1, job.id(), "media_error");
        assertTrue(service.get(1, job.id()).interrupted()); assertEquals("media_error", service.get(1, job.id()).endReason());
        assertTrue(service.stop(1, job.id(), "call_end").interrupted());
        properties.setPrepareSeconds(-1);
        Process preparingProcess = mock(Process.class);
        doReturn(new CompletableFuture<Process>()).when(preparingProcess).onExit();
        doReturn(preparingProcess).when(media).capture(anyString(), anyString(), any(), any());
        var preparing = service.start(3, "call_other_123", 4, "request_timeout", false, false, false);
        service.checkCaptures();
        var timeout = service.stop(3, preparing.id(), "call_end");
        assertTrue(timeout.interrupted()); assertEquals("prepare_timeout", timeout.endReason());
    }

    @Test void normalCallEndStopsBothParticipantsButManualStopIsPrivate() throws Exception {
        var first = start("request_first");
        var second = service.start(2, "call_test_123", 1, "request_second", false, false, false);
        service.stop(1, first.id()); assertEquals("recording", service.get(2, second.id()).state());
        service.stop(1, first.id(), "call_end");
        var peer = service.get(2, second.id());
        assertFalse(peer.interrupted()); assertEquals("call_end", peer.endReason());
        assertThrows(BusinessException.class, () -> service.stop(3, second.id(), "call_end"));
        assertThrows(BusinessException.class, () -> service.stop(2, second.id(), "invalid"));
    }

    private Process preparingProcess() {
        Process process = mock(Process.class); var exit = new CompletableFuture<Process>();
        exits.put(process, exit); when(process.onExit()).thenReturn(exit); return process;
    }
    private void awaitState(String id, String... states) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!java.util.List.of(states).contains(service.get(1, id).state()) && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(java.util.List.of(states).contains(service.get(1, id).state()), service.get(1, id).toString());
    }
    @Test void missingFirstKeyframeRetriesSameJobAndIgnoresOldProgress() throws Exception {
        var callbacks = new java.util.concurrent.CopyOnWriteArrayList<LongConsumer>();
        doAnswer(invocation -> {
            callbacks.add(invocation.getArgument(3)); return preparingProcess();
        }).when(media).capture(anyString(), anyString(), any(), any());
        var job = start("request_keyframe"); var first = exits.keySet().iterator().next();
        Files.writeString(directory.resolve(job.id()).resolve("capture.log"), "Keyframe missing");
        exits.get(first).complete(first);
        verify(media, timeout(4000).times(2)).capture(anyString(), anyString(), any(), any());
        callbacks.getFirst().accept(1_000_000); assertEquals("starting", service.get(1, job.id()).state());
        callbacks.get(1).accept(2_000_000); assertEquals("recording", service.get(1, job.id()).state());
        assertEquals(job.id(), start("request_keyframe").id());
        assertTrue(Files.readString(directory.resolve(job.id()).resolve("attempt-1-capture.log")).contains("Keyframe missing"));
        service.stop(1, job.id()); awaitState(job.id(), "ready");
    }
    @Test void stopDuringStartupCancelsRetryAndDoesNotComposeEmptyMaterial() throws Exception {
        doAnswer(invocation -> preparingProcess()).when(media).capture(anyString(), anyString(), any(), any());
        var job = start("request_cancel"); var first = exits.keySet().iterator().next();
        exits.get(first).complete(first); service.stop(1, job.id(), "call_end");
        awaitState(job.id(), "failed"); Thread.sleep(450);
        verify(media, times(1)).capture(anyString(), anyString(), any(), any()); verify(media, never()).compose(any(), any());
        assertFalse(service.get(1, job.id()).retryable());
        assertFalse(service.get(1, job.id()).message().contains("可重试"));
        assertThrows(BusinessException.class, () -> service.retry(1, job.id()));
    }
    @Test void startupRetriesAreBoundedWithoutCreatingNewTasks() throws Exception {
        properties.setStartupAttempts(2);
        doAnswer(invocation -> {
            Process process = preparingProcess(); exits.get(process).complete(process); return process;
        }).when(media).capture(anyString(), anyString(), any(), any());
        var job = start("request_bounded"); awaitState(job.id(), "failed");
        verify(media, times(2)).capture(anyString(), anyString(), any(), any()); verify(media, never()).compose(any(), any());
        assertEquals(1, service.list(1).size()); assertEquals(0, service.get(1, job.id()).elapsedMs());
    }
    @Test void usableMaterialIsNeverOverwrittenByStartupRetry() throws Exception {
        when(media.hasUsableCapture(any())).thenReturn(true);
        doAnswer(invocation -> {
            Process process = preparingProcess(); exits.get(process).complete(process); return process;
        }).when(media).capture(anyString(), anyString(), any(), any());
        var job = start("request_preserve"); awaitState(job.id(), "ready");
        verify(media, times(1)).capture(anyString(), anyString(), any(), any()); verify(media, times(1)).compose(any(), any());
    }
    @Test void onlyComposeFailuresWithUsableOriginalsOfferRetry() throws Exception {
        when(media.hasUsableCapture(any())).thenReturn(true);
        doThrow(new java.io.IOException("encoder failed")).when(media).compose(any(), any());
        var job = start("request_compose"); service.stop(1, job.id()); awaitState(job.id(), "failed");
        assertTrue(service.get(1, job.id()).retryable()); assertTrue(service.get(1, job.id()).message().contains("素材已保留"));
        doAnswer(invocation -> {
            Files.write(((Path)invocation.getArgument(1)).resolve("result.mp4"), new byte[2048]);
            return new CallRecordingMedia.Probe(0, 5.5, true, true, "h264", "aac");
        }).when(media).compose(any(), any());
        service.retry(1, job.id()); awaitState(job.id(), "ready");
    }
    @Test void generationBlocksNextRecordingUntilItCompletes() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown(); assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Files.write(((Path)invocation.getArgument(1)).resolve("result.mp4"), new byte[2048]);
            return new CallRecordingMedia.Probe(0, 5.5, true, true, "h264", "aac");
        }).when(media).compose(any(), any());
        var job = start("request_first_wait"); service.stop(1, job.id());
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("processing", service.get(1, job.id()).state());
            assertThrows(BusinessException.class, () -> start("request_next_wait"));
            verify(media, times(1)).capture(anyString(), anyString(), any(), any());
        } finally { release.countDown(); }
        awaitState(job.id(), "ready"); assertEquals("recording", start("request_next_wait").state());
    }
    @Test void recordingKeepsPeerIdentityAndDisplayNameAcrossRestart() throws Exception {
        var job = start("request_name"); assertEquals(2, job.peerId()); assertEquals("张三", job.peerName());
        service.stop(1, job.id()); awaitState(job.id(), "ready"); service.close();
        when(users.selectById(2L)).thenReturn(User.builder().username("peer2").nickname("新名字").build());
        service = new CallRecordingServiceImpl(properties, media, presence, new ObjectMapper(), users); service.init();
        assertEquals("张三", service.get(1, job.id()).peerName(), "录像保留录制时的通话对象名称");
        CallRecordingJob stored = new ObjectMapper().readValue(directory.resolve(job.id()).resolve("job.json").toFile(), CallRecordingJob.class);
        stored.setPeerName(""); new ObjectMapper().writeValue(directory.resolve(job.id()).resolve("job.json").toFile(), stored);
        service.close(); service = new CallRecordingServiceImpl(properties, media, presence, new ObjectMapper(), users); service.init();
        assertEquals("新名字", service.list(1).getFirst().peerName(), "旧任务可补齐真实名称");
    }
}
