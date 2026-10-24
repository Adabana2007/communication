package hnist.communication.manager.camera;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sun.net.httpserver.HttpServer;
import hnist.communication.dto.CameraRequest;
import hnist.communication.entity.Camera;
import hnist.communication.mapper.CameraMapper;
import hnist.communication.manager.camera.impl.CameraCompatibilityImpl;
import hnist.communication.manager.camera.impl.CameraDiagnosticsImpl;
import hnist.communication.service.MediaMtxService;
import hnist.communication.service.OnvifService;
import hnist.communication.service.impl.CameraServiceImpl;
import hnist.communication.service.impl.MediaMtxServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 无 Spring 上下文、数据库或现场摄像头依赖。 */
class CameraMediaTest {
    CameraMapper mapper; CameraMediaGateway gateway; CameraDiagnostics diagnostics; CameraCompatibility compatibility;
    MediaMtxService media; CameraServiceImpl service; Camera camera;
    CameraMediaProperties mediaProperties;
    @BeforeEach void setup() {
        mapper = mock(CameraMapper.class); gateway = mock(CameraMediaGateway.class); diagnostics = mock(CameraDiagnostics.class);
        compatibility = mock(CameraCompatibility.class); media = mock(MediaMtxService.class);
        mediaProperties = new CameraMediaProperties();
        mediaProperties.setPlayReadyTimeoutMs(0);   // 单测不等就绪，只验证"如实回报"
        service = new CameraServiceImpl(mapper, mock(OnvifService.class), media, gateway, diagnostics, compatibility, mediaProperties);
        camera = Camera.builder().id(7L).name("门口").ip("192.0.2.7").rtspUrl("rtsp://192.0.2.7/live")
                .mediamtxPath("cam_7").sourceProtocol("tcp").lastError("旧兼容性提示").build();
        when(mapper.selectById(7L)).thenReturn(camera);
        when(media.buildWebrtcUrl(anyString())).thenAnswer(i -> "http://media:8889/" + i.getArgument(0));
        when(media.buildHlsUrl(anyString())).thenAnswer(i -> "http://media:8888/" + i.getArgument(0) + "/index.m3u8");
    }
    void snapshot(boolean available, Map<String, CameraMediaGateway.PathState> paths) {
        when(gateway.snapshot()).thenReturn(new CameraMediaGateway.Snapshot(available, Instant.now(), paths));
    }
    @Test void onlineClearsCurrentErrorAndExplicitlyWritesDatabaseNull() {
        snapshot(true, Map.of("cam_7", new CameraMediaGateway.PathState(true, List.of("H264"), List.of(new CameraMediaGateway.TrackInfo("H264", null, null)), 1)));
        var vo = service.getCamera(7L);
        assertEquals("ready", vo.getSourceState()); assertEquals(1, vo.getStatus()); assertNull(vo.getLastError());
        var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("last_error IS NOT NULL"));
        assertTrue(((com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?>) captor.getValue()).getSqlSet().contains("last_error="));
    }
    @Test void apiUnavailableIsUnknownNotOffline() {
        snapshot(false, Map.of()); var vo = service.getCamera(7L);
        assertEquals(-1, vo.getStatus()); assertEquals("unknown", vo.getSourceState()); assertNull(vo.getLastError());
    }
    @Test void unreadyHasNoInventedCredentialOrCodecError() {
        snapshot(true, Map.of()); var vo = service.getCamera(7L);
        assertEquals("not_ready", vo.getSourceState()); assertNull(vo.getLastError());
        assertFalse(vo.getSourceMessage().contains("H.264"));
    }
    @Test void listUsesOneSnapshotForAllCameras() {
        snapshot(true, Map.of()); when(mapper.selectList(any())).thenReturn(List.of(camera, camera, camera));
        assertEquals(3, service.listCameras().size()); verify(gateway, times(1)).snapshot(); verifyNoInteractions(compatibility);
    }
    @Test void refreshIsReadOnlyAndNeverRecreatesPath() {
        snapshot(true, Map.of()); service.refreshStream(7L);
        verify(media, never()).recreatePath(any(), any(), any()); verify(media, never()).removePath(any());
    }
    @Test void reconnectIsExplicitAndScopedToOneSource() {
        snapshot(true, Map.of()); service.reconnectStream(7L);
        verify(media).recreatePath(7L, camera.getRtspUrl(), "tcp"); verify(diagnostics).invalidate(7L);
    }
    @Test void existingOutputIsUsedByBothUrlsWithoutResettingIt() {
        camera.setPlaybackMode("existing"); camera.setPlaybackPath("converted/door"); snapshot(true, Map.of());
        var vo = service.preparePlayback(7L);
        assertEquals("http://media:8889/converted/door", vo.getWebrtcUrl()); assertTrue(vo.getHlsUrl().contains("converted/door"));
        verify(media, never()).removePath(any());
    }
    @Test void directModeIgnoresAnOldOverride() {
        camera.setPlaybackMode("direct"); camera.setPlaybackPath("old"); assertEquals("cam_7", CameraMediaUtils.playbackPath(camera));
    }
    @Test void invalidEditDoesNotDeleteWorkingSource() {
        CameraRequest request = new CameraRequest(); request.setRtspUrl("http://invalid");
        assertThrows(RuntimeException.class, () -> service.updateCamera(7L, request)); verify(media, never()).removePath(any());
    }
    @Test void pathsCannotInjectUrlsQueryOrTraversal() {
        for (String path : List.of("../cam", "rtsp://host/a", "cam?a=1", "cam%2fa", "cam#x", "cam;whoami"))
            assertThrows(RuntimeException.class, () -> CameraMediaGateway.validPath(path));
        assertEquals("existing/cam_7", CameraMediaGateway.validPath("existing/cam_7"));
    }
    @Test void snapshotsIncludeEveryPageAndCacheRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger(); HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/paths/list", exchange -> {
            requests.incrementAndGet(); boolean first = exchange.getRequestURI().getQuery().contains("page=0");
            String body = "{\"pageCount\":2,\"items\":[{\"name\":\"cam_" + (first ? "1" : "7") + "\",\"ready\":true,\"tracks\":[\"H264\"],\"readers\":[]}]}";
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length());
            exchange.getResponseBody().write(body.getBytes()); exchange.close();
        }); server.start();
        try {
            var real = new CameraMediaGateway("http://127.0.0.1:" + server.getAddress().getPort(), new CameraMediaProperties());
            var state = real.snapshot(); assertTrue(state.available()); assertTrue(state.paths().get("cam_7").ready());
            assertSame(state, real.snapshot()); assertEquals(2, requests.get());
        } finally { server.stop(0); }
    }
    @Test void compatibilityDisabledAndMissingEvidenceCannotStartProcess() {
        CameraMediaProperties props = new CameraMediaProperties(); camera.setPlaybackMode("compatible");
        var compat = new CameraCompatibilityImpl(props, gateway, diagnostics, new MediaToolLocator(props));
        assertThrows(RuntimeException.class, () -> compat.prepare(camera)); props.setCompatibilityEnabled(true);
        assertThrows(RuntimeException.class, () -> compat.prepare(camera)); verify(gateway, never()).ensurePublisher(any()); compat.close();
    }
    @Test void persistedSourceApprovalSurvivesDiagnosticCacheExpiryButDoesNotMatchChangedSource() {
        CameraMediaProperties props = new CameraMediaProperties(); props.setCompatibilityEnabled(true); props.setFfmpeg("nonexistent-camera-test-encoder");
        snapshot(true, Map.of("cam_7", new CameraMediaGateway.PathState(true, List.of("H265"), List.of(new CameraMediaGateway.TrackInfo("H265", null, null)), 0)));
        camera.setPlaybackMode("compatible"); camera.setCompatibilitySourceHash(CameraMediaUtils.sourceFingerprint(camera));
        var compat = new CameraCompatibilityImpl(props, gateway, diagnostics, new MediaToolLocator(props));
        when(gateway.rtspUrl(any())).thenReturn("rtsp://127.0.0.1:8554/test");
        assertThrows(RuntimeException.class, () -> compat.prepare(camera)); verify(gateway).ensurePublisher("cam_7_compatible");
        camera.setPassword("changed"); camera.setUsername("user"); clearInvocations(gateway);
        assertThrows(RuntimeException.class, () -> compat.prepare(camera)); verify(gateway, never()).ensurePublisher(any()); compat.close();
    }
    @Test void enablingCompatibleModeRequiresSourceEvidenceBeforeAnyStreamMutation() {
        CameraRequest req = new CameraRequest(); req.setPlaybackMode("compatible"); req.setRtspUrl(camera.getRtspUrl());
        assertThrows(RuntimeException.class, () -> service.updateCamera(7L, req)); verifyNoInteractions(media, compatibility);
    }
    @Test void encoderCommandProducesBaselineWithoutBFramesAndOptionalAudio() {
        var cmd = CameraCompatibilityUtils.command("ffmpeg", "rtsp://127.0.0.1/in", "rtsp://127.0.0.1/out");
        assertEquals("baseline", cmd.get(cmd.indexOf("-profile:v") + 1)); assertEquals("0", cmd.get(cmd.indexOf("-bf") + 1));
        assertTrue(cmd.contains("0:a:0?")); assertTrue(cmd.contains("libopus")); assertTrue(cmd.contains("yuv420p"));
    }
    @Test void diagnosticsAreDeduplicatedAndBoundedAndStaleCompletionCannotOverwrite() throws Exception {
        CameraMediaProperties props = new CameraMediaProperties(); props.setProbeWorkers(1);
        var probe = mock(CameraStreamProbe.class); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        var sample = new CameraStreamProbe.Sample(true, true, "OK", "可读", List.of());
        when(probe.probe(any(), any())).thenAnswer(i -> { started.countDown(); release.await(2, TimeUnit.SECONDS); return sample; });
        when(gateway.rtspUrl(any())).thenReturn("rtsp://127.0.0.1:8554/cam_7");
        var real = new CameraDiagnosticsImpl(mapper, gateway, probe, props);
        try {
            var first = real.start(7L); assertTrue(started.await(1, TimeUnit.SECONDS)); assertSame(first, real.start(7L));
            var other = Camera.builder().id(8L).rtspUrl("rtsp://192.0.2.8/live").mediamtxPath("cam_8").build();
            when(mapper.selectById(8L)).thenReturn(other); assertThrows(RuntimeException.class, () -> real.start(8L));
            real.invalidate(7L); release.countDown(); Thread.sleep(50); assertNull(real.current(camera));
        } finally { release.countDown(); real.close(); }
    }
    @Test void currentAndLegacyTransportFieldsDoNotCauseRepeatedSourceResets() {
        for (String field : List.of("rtspTransport", "sourceProtocol")) {
            RestTemplate http = new RestTemplate(); var server = MockRestServiceServer.createServer(http);
            server.expect(requestTo("http://media/v3/config/paths/get/cam_7")).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"source\":\"rtsp://camera/live\",\"" + field + "\":\"tcp\"}", MediaType.APPLICATION_JSON));
            var real = new MediaMtxServiceImpl(http, "http://media", "", "", "");
            assertEquals("cam_7", real.ensurePath(7L, "rtsp://camera/live", "tcp")); server.verify();
        }
    }
    @Test void failedConfigurationLookupNeverDeletesOrAdds() {
        RestTemplate http = new RestTemplate(); var server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://media/v3/config/paths/get/cam_7")).andRespond(withServerError());
        var real = new MediaMtxServiceImpl(http, "http://media", "", "", "");
        assertThrows(RuntimeException.class, () -> real.ensurePath(7L, "rtsp://camera/live", "tcp")); server.verify();
    }
    /** 源头没就绪时不得把直连地址当"可播"交出去：如实回报 not_ready，交由客户端等待。 */
    @Test void unreadySourceIsReportedAsNotReadyInsteadOfBeingHandedOutAsPlayable() {
        snapshot(true, Map.of());
        var vo = service.preparePlayback(7L);
        assertEquals("cam_7", vo.getPlaybackPath());
        assertEquals("not_ready", vo.getPlaybackSourceState());
        verify(compatibility, never()).prepareAuto(any());
    }
    /** 源需要兼容输出（LPCM 44.1kHz 不被 WebRTC 支持）时启动转码，并如实报告兼容路径尚未就绪。 */
    @Test void sourceThatNeedsCompatibilityStartsTranscodeAndDoesNotClaimReady() {
        snapshot(true, Map.of("cam_7", new CameraMediaGateway.PathState(true, List.of("H264", "LPCM"),
                List.of(new CameraMediaGateway.TrackInfo("H264", null, null), new CameraMediaGateway.TrackInfo("LPCM", 44100, 16)), 0)));
        var vo = service.preparePlayback(7L);
        verify(compatibility).prepareAuto(camera);
        assertEquals("cam_7_compatible", vo.getPlaybackPath());
        assertEquals("http://media:8889/cam_7_compatible", vo.getWebrtcUrl());
        assertEquals("not_ready", vo.getPlaybackSourceState());
    }
}
