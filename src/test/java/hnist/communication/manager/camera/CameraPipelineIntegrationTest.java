package hnist.communication.manager.camera;

import com.fasterxml.jackson.databind.ObjectMapper;
import hnist.communication.entity.Camera;
import hnist.communication.mapper.CameraMapper;
import hnist.communication.service.impl.MediaMtxServiceImpl;
import hnist.communication.manager.camera.impl.CameraCompatibilityImpl;
import hnist.communication.manager.camera.impl.CameraDiagnosticsImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离 MediaMTX、真实 H.265 源、生产诊断/编码器与真实 Chromium；绝不启动业务应用或访问数据库。 */
class CameraPipelineIntegrationTest {
    @TempDir Path directory;
    @Test void diagnosesHevcAndSharesCompatibleEncoderAndPlaysRealFrames() throws Exception {
        String binary = System.getenv("CAMERA_TEST_MEDIAMTX"), fixture = System.getenv("CAMERA_TEST_FIXTURE");
        Assumptions.assumeTrue(binary != null && fixture != null && Files.isRegularFile(Path.of(binary)) && Files.isRegularFile(Path.of(fixture)), "Set CAMERA_TEST_MEDIAMTX and CAMERA_TEST_FIXTURE for isolated live test");
        int rtsp = port(), api = port(), webrtc = port();
        Path conf = directory.resolve("mediamtx.yml");
        Files.writeString(conf, "logLevel: warn\nrtspAddress: 127.0.0.1:" + rtsp + "\nrtspTransports: [tcp]\napi: yes\napiAddress: 127.0.0.1:" + api +
                "\nrtmp: no\nhls: no\nsrt: no\nplayback: no\nwebrtc: yes\nwebrtcAddress: 127.0.0.1:" + webrtc +
                "\nwebrtcLocalUDPAddress: 127.0.0.1:0\nwebrtcAdditionalHosts: [127.0.0.1]\npaths:\n  all_others:\n");
        Process server = new ProcessBuilder(binary, conf.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("mediamtx.log").toFile()).start();
        Process publisher = null; CameraCompatibilityImpl compatibility = null; CameraDiagnosticsImpl diagnostics = null;
        try {
            for (int n = 0; n < 100; n++) { try (Socket socket = new Socket("127.0.0.1", rtsp)) { break; } catch (Exception e) { Thread.sleep(50); } }
            String base = "rtsp://127.0.0.1:" + rtsp;
            publisher = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-re", "-stream_loop", "-1", "-i", fixture,
                    "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=error:keyint=30:bframes=2", "-c:a", "copy",
                    "-f", "rtsp", "-rtsp_transport", "tcp", base + "/source")
                    .redirectError(directory.resolve("publish.log").toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            var props = new CameraMediaProperties(); props.setRtspBase(base); props.setCompatibilityEnabled(true); props.setProbeSeconds(8); props.setMaxTranscodes(1);
            var json = new ObjectMapper(); var gateway = new CameraMediaGateway("http://127.0.0.1:" + api, props);
            var service = new MediaMtxServiceImpl(new RestTemplate(), "http://127.0.0.1:" + api, "", "", "");
            service.ensurePath(7L, base + "/source", "tcp");
            boolean ready = false;
            for (int n = 0; n < 40; n++) {
                gateway.invalidate(); var state = gateway.snapshot().paths().get("cam_7");
                if (state != null && state.ready()) { ready = true; break; } Thread.sleep(200);
            }
            assertTrue(ready, () -> read(directory.resolve("publish.log")) + read(directory.resolve("mediamtx.log")));
            var camera = Camera.builder().id(7L).rtspUrl(base + "/source").sourceProtocol("tcp").mediamtxPath("cam_7").playbackMode("compatible").build();
            var mapper = mock(CameraMapper.class); when(mapper.selectById(7L)).thenReturn(camera);
            var locator = new MediaToolLocator(props); locator.resolve();
            var probe = new CameraStreamProbe(props, locator, json); diagnostics = new CameraDiagnosticsImpl(mapper, gateway, probe, props);
            diagnostics.start(7L); CameraDiagnostics.Report report = null;
            for (int n = 0; n < 120; n++) { report = diagnostics.current(camera); if (report != null && !"running".equals(report.state())) break; Thread.sleep(200); }
            assertNotNull(report); assertEquals("completed", report.state()); assertTrue(report.original().needsCompatibility(), report.toString());
            compatibility = new CameraCompatibilityImpl(props, gateway, diagnostics, locator); compatibility.prepare(camera); compatibility.prepare(camera);
            var other = Camera.builder().id(8L).rtspUrl(base + "/source").sourceProtocol("tcp").mediamtxPath("cam_7").playbackMode("compatible").build();
            other.setCompatibilitySourceHash(CameraMediaUtils.sourceFingerprint(other));
            final CameraCompatibilityImpl bounded = compatibility;
            assertTrue(assertThrows(RuntimeException.class, () -> bounded.prepare(other)).getMessage().contains("资源已满"));
            ready = false;
            for (int n = 0; n < 60; n++) {
                gateway.invalidate(); var state = gateway.snapshot().paths().get("cam_7_compatible");
                if (state != null && state.ready()) { ready = true; break; } Thread.sleep(200);
            }
            assertTrue(ready, () -> read(directory.resolve("mediamtx.log")));
            var compatible = probe.probe(base + "/cam_7_compatible", "tcp");
            assertTrue(compatible.videoFrame(), compatible.toString()); assertFalse(compatible.needsCompatibility());
            var video = compatible.tracks().stream().filter(t -> t.type().equals("video")).findFirst().orElseThrow();
            assertEquals("h264", video.codec()); assertEquals(0, video.bFrames()); assertTrue(video.profile().contains("Baseline"));
            assertEquals("yuv420p", video.pixelFormat()); assertEquals(640, video.width()); assertEquals(480, video.height());
            assertTrue(compatible.tracks().stream().anyMatch(t -> "opus".equals(t.codec())));
            String output = System.getenv("CAMERA_TEST_OUTPUT");
            if (output != null) {
                Path out = Path.of(output); Files.createDirectories(out);
                json.writeValue(out.resolve("compatible-probe.json").toFile(), compatible);
                json.writeValue(out.resolve("original-probe.json").toFile(), report.original());
                Process browser = new ProcessBuilder("D:/New Folder/Node.js/node.exe", "../callapp/scripts/verify-camera-browser.mjs",
                        "http://127.0.0.1:" + webrtc + "/cam_7_compatible", out.toAbsolutePath().toString())
                        .redirectErrorStream(true).redirectOutput(out.resolve("browser.log").toFile()).start();
                assertTrue(browser.waitFor(40, TimeUnit.SECONDS)); assertEquals(0, browser.exitValue(), () -> read(out.resolve("browser.log")));
            }
            Thread.sleep(31000); // 最后一个真实浏览器/探测读者离开后的空闲回收期限。
            compatibility.reap(); gateway.invalidate();
            for (int n = 0; n < 20; n++) {
                var state = gateway.snapshot().paths().get("cam_7_compatible");
                if (state == null || !state.ready()) break; Thread.sleep(100); gateway.invalidate();
            }
            var finalState = gateway.snapshot().paths().get("cam_7_compatible");
            assertTrue(finalState == null || !finalState.ready(), "最后读者离开后应停止编码器");
            assertTrue(gateway.snapshot().paths().get("cam_7").ready(), "回收兼容输出不能删除或停止原始源");
            System.out.println("VERIFIED HEVC -> H264 Baseline/yuv420p/0 B-frames + Opus; duplicated prepare reused publisher; isolated pipeline");
        } finally {
            if (compatibility != null) compatibility.close(); if (diagnostics != null) diagnostics.close();
            for (Process p : new Process[]{publisher, server}) if (p != null) { p.destroy(); if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly(); }
        }
    }
    private static int port() throws Exception { try (var s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return s.getLocalPort(); } }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception ignored) { return "missing log"; } }
}
