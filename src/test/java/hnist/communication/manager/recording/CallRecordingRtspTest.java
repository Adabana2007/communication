package hnist.communication.manager.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestTemplate;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.impl.CallRecordingServiceImpl;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 独立随机端口 MediaMTX + 两路实际 RTSP 发布 + 完整录制任务；不连接业务数据库。 */
class CallRecordingRtspTest {
    @TempDir Path directory;
    @ParameterizedTest
    @CsvSource({"h264,30", "vp8,30", "vp8,180"})
    void recordsTwoLiveStreamsAndExportsNormalMp4(String codec, int gop) throws Exception {
        String binary = System.getenv("CALL_RECORDING_TEST_MEDIAMTX");
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(Path.of(binary)), "Set CALL_RECORDING_TEST_MEDIAMTX for live RTSP test");
        int rtspPort = port(), apiPort = port();
        Path conf = directory.resolve("mediamtx.yml");
        Files.writeString(conf, "logLevel: warn\nrtspAddress: 127.0.0.1:" + rtspPort + "\nrtspTransports: [tcp]\n" +
            "api: yes\napiAddress: 127.0.0.1:" + apiPort + "\nrtmp: no\nhls: no\nwebrtc: no\nsrt: no\nmoq: no\nplayback: no\npaths:\n  all_others:\n");
        Process server = new ProcessBuilder(binary, conf.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("mediamtx.log").toFile()).start();
        List<Process> publishers = new ArrayList<>(); CallRecordingServiceImpl service = null;
        try {
            for (int i = 0; i < 100; i++) { try (Socket socket = new Socket("127.0.0.1", rtspPort)) { break; } catch (Exception e) { Thread.sleep(50); } }
            String base = "rtsp://127.0.0.1:" + rtspPort, room = "call_live_fixture";
            fixture(directory.resolve("red.mkv"), "red", "1000", codec, gop); fixture(directory.resolve("green.mkv"), "lime", "600", codec, gop);
            for (int user = 1; user <= 2; user++) publishers.add(new ProcessBuilder("ffmpeg", "-v", "warning", "-re", "-stream_loop", "-1", "-i",
                directory.resolve(user == 1 ? "red.mkv" : "green.mkv").toString(), "-c", "copy", "-rtsp_transport", "tcp", "-f", "rtsp",
                base + "/" + CallMediaPresence.path(room, user)).redirectErrorStream(true).redirectOutput(directory.resolve("publish-" + user + ".log").toFile()).start());
            var config = new CallRecordingProperties(); config.setStorage(directory.resolve("jobs").toString()); config.setMinFreeBytes(0); config.setRtspBase(base); config.setComposeSeconds(120);
            var mapper = new ObjectMapper(); var media = new CallRecordingMedia(config, mapper);
            var presence = new CallMediaPresence(new RestTemplate(), "http://127.0.0.1:" + apiPort);
            boolean ready = false;
            for (int i = 0; i < 20; i++) { var health = presence.pulse(1, room, 2, false, false); if (health.known() && health.localReady() && health.peerReady()) { ready = true; break; } Thread.sleep(250); }
            assertTrue(ready, () -> read(directory.resolve("mediamtx.log")));
            presence.pulse(2, room, 1, false, false);
            if (gop > 30) Thread.sleep(1500); // 在首个关键帧之后订阅，复现启动偶发漏帧的时间窗口。
            assertTrue(presence.pulse(1, room, 2, false, false).peerAgeMs() >= 0);
            service = new CallRecordingServiceImpl(config, media, presence, mapper, org.mockito.Mockito.mock(UserMapper.class)); service.init();
            var job = service.start(1, room, 2, "live_request_123", false, false, false);
            for (int i = 0; i < 100 && service.get(1, job.id()).state().equals("starting"); i++) { Thread.sleep(200); service.checkCaptures(); }
            assertEquals("recording", service.get(1, job.id()).state(), () -> read(directory.resolve("jobs").resolve(job.id()).resolve("capture.log")));
            Thread.sleep(2500); service.layout(1, job.id(), true, false, false); Thread.sleep(2500); service.stop(1, job.id());
            long deadline = System.currentTimeMillis() + 120000;
            while (service.get(1, job.id()).state().equals("processing") && System.currentTimeMillis() < deadline) Thread.sleep(200);
            var result = service.get(1, job.id());
            assertEquals("ready", result.state(), () -> read(directory.resolve("jobs").resolve(job.id()).resolve("compose.log")));
            assertTrue(result.duration() > 3 && result.duration() < 15, result.toString());
            assertEquals(result.elapsedMs() / 1000.0, result.duration(), .5, "成片不得包含准备阶段缓冲帧");
            var probe = media.probe(service.file(1, job.id())); assertEquals("h264", probe.videoCodec()); assertEquals("aac", probe.audioCodec()); assertEquals(0, probe.start(), .01);
            String output = System.getenv("CALL_RECORDING_TEST_OUTPUT");
            assertEquals("green", pixel(service.file(1, job.id()), .4)); assertEquals("red", pixel(service.file(1, job.id()), result.duration() - .4));
            if (output != null) { Path out = Path.of(output); Files.createDirectories(out); Files.copy(service.file(1, job.id()), out.resolve("live-rtsp-" + codec + "-recording.mp4"), StandardCopyOption.REPLACE_EXISTING); mapper.writeValue(out.resolve("live-rtsp-" + codec + "-report.json").toFile(), result); }
            System.out.println("VERIFIED LIVE RTSP: " + result);
        } catch (Exception | AssertionError e) {
            System.out.println(read(directory.resolve("mediamtx.log")));
            String output = System.getenv("CALL_RECORDING_TEST_OUTPUT");
            if (output != null) try (var paths = Files.walk(directory)) {
                Path retained = Path.of(output).resolve("rtsp-failed-" + codec + "-" + gop); Files.createDirectories(retained);
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    Path target = retained.resolve(directory.relativize(file)); Files.createDirectories(target.getParent()); Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            throw e;
        }
        finally { if (service != null) service.close(); for (Process p : publishers) { p.destroy(); if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly(); } server.destroy(); if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly(); }
    }
    private static int port() throws Exception { try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private void fixture(Path path, String color, String tone, String codec, int gop) throws Exception {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=" + color + ":s=640x480:r=30:d=15",
            "-f", "lavfi", "-i", "sine=frequency=" + tone + ":duration=15"));
        command.addAll(codec.equals("h264") ? List.of("-c:v", "libx264", "-preset", "ultrafast", "-profile:v", "baseline") :
            List.of("-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "500k"));
        command.addAll(List.of("-g", String.valueOf(gop), "-pix_fmt", "yuv420p", "-c:a", "libopus", "-shortest", path.toString()));
        Process p = new ProcessBuilder(command).redirectError(directory.resolve(color + ".log").toFile()).start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, p.exitValue());
    }
    private String pixel(Path video, double at) throws Exception {
        Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-ss", String.valueOf(at), "-i", video.toString(), "-frames:v", "1", "-vf", "crop=2:2:50:50,format=rgb24", "-f", "rawvideo", "pipe:1").start();
        byte[] rgb = p.getInputStream().readAllBytes(); assertTrue(p.waitFor(10, TimeUnit.SECONDS)); assertTrue(rgb.length >= 3);
        return Byte.toUnsignedInt(rgb[0]) > Byte.toUnsignedInt(rgb[1]) ? "red" : "green";
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception e) { return "missing " + path; } }
}
