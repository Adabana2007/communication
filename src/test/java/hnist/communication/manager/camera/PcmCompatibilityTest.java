package hnist.communication.manager.camera;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PcmCompatibilityTest {
    @TempDir Path directory;

    /**
     * 隔离用的 MediaMTX 配置：只开 RTSP，其余服务（含 Control API :9997、MoQ :8892）全部关闭。
     * 本机若已在运行 MediaMTX，这些默认端口会冲突并让测试实例启动即退出。
     */
    private static String mediamtxConfig(int port) {
        return "logLevel: warn\nrtspAddress: 127.0.0.1:" + port + "\nrtspTransports: [tcp]\n"
                + "api: no\nmetrics: no\npprof: no\nmoq: no\nrtmp: no\nhls: no\nwebrtc: no\nsrt: no\nplayback: no\n"
                + "paths:\n  all_others:\n";
    }

    /** 等 RTSP 端口可连；进程已退出则立即失败并带上服务端日志，避免伪装成管线超时。 */
    private static void waitForRtsp(Process server, int port, Path log) throws Exception {
        for (int i = 0; i < 100; i++) {
            try (var socket = new Socket("127.0.0.1", port)) { return; }
            catch (Exception e) { if (!server.isAlive()) break; Thread.sleep(50); }
        }
        fail("隔离 MediaMTX 未启动：" + read(log));
    }

    /** 收尾必须等到进程真的退出：只 destroyForcibly 不等退出会继续占用日志文件句柄，令临时目录删除失败。 */
    private static void stop(Process... processes) {
        for (Process p : processes) {
            if (p == null) continue;
            try {
                p.destroy();
                if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); p.waitFor(3, TimeUnit.SECONDS); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * 管线日志写到系统临时目录而不是 {@code @TempDir}：Windows 上 MediaMTX/ffmpeg 退出后
     * 日志文件句柄释放略慢，留在受管目录里会让 JUnit 清理报 "Failed to close extension context"。
     */
    private static Path pipeLog(String name) throws java.io.IOException {
        return Files.createTempFile("camera-pcm-" + name + "-", ".log");
    }

    @Test void timestampCompensationIsScopedToPcm() {
        var pcm = CameraCompatibilityUtils.command("ffmpeg", "rtsp://localhost/in", "rtsp://localhost/out", true);
        assertTrue(pcm.contains("aresample=48000:async=100:min_hard_comp=0.1"));
        assertFalse(CameraCompatibilityUtils.command("ffmpeg", "in", "out").contains("-af"));
        assertFalse(pcm.contains("-f s16le"), "不得臆测源PCM字节序");
    }

    /** 私有小端 PCM 只有显式声明时才换字节序和重标采样率；标准 L16 源必须保持原样。 */
    @Test void byteSwapAndRealRateAreOnlyAppliedWhenExplicitlyDeclared() {
        var standard = CameraCompatibilityUtils.command("ffmpeg", "in", "out", true, false, 16000);
        assertEquals("aresample=48000:async=100:min_hard_comp=0.1", audioFilter(standard));

        var littleEndian = CameraCompatibilityUtils.command("ffmpeg", "in", "out", true, true, 16000);
        assertEquals("aeval=exprs='" + CameraCompatibilityUtils.LE_SAMPLE_BYTESWAP
                        + "':c=same,asetrate=16000,aresample=48000,asetpts=N/SR/TB", audioFilter(littleEndian));
        assertFalse(littleEndian.contains("-f s16le"), "不得用裸流参数臆测源字节序");
    }

    private static String audioFilter(List<String> command) {
        return command.get(command.indexOf("-af") + 1);
    }

    /**
     * 复刻现场故障：源用负载类型 11 发私有小端 PCM，MediaMTX 按 L16（大端、44100Hz）转发。
     * 未修正时读到的是满量程噪声；开启 pcm-little-endian 后必须还原成干净的 1000Hz 正弦。
     */
    @Test void littleEndianSourceIsRestoredToACleanTone() throws Exception {
        String binary = System.getenv("CAMERA_TEST_MEDIAMTX");
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(Path.of(binary)), "Set CAMERA_TEST_MEDIAMTX for isolated PCM test");
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        Path conf = directory.resolve("mediamtx-le.yml");
        Files.writeString(conf, mediamtxConfig(port));
        Path serverLog = pipeLog("server-le"), publishLog = pipeLog("publish-le"),
                encodeLog = pipeLog("encode-le"), readLog = pipeLog("read-le");
        Process server = new ProcessBuilder(binary, conf.toString()).redirectErrorStream(true).redirectOutput(serverLog.toFile()).start();
        Process publisher = null, encoder = null, reader = null;
        try {
            waitForRtsp(server, port, serverLog);
            String base = "rtsp://127.0.0.1:" + port;
            // 1000Hz@16kHz 的正弦 -> 换字节序，复刻设备的私有小端 PCM。
            // 注意：MediaMTX 对"外部推流"沿用推流侧时钟，无法复现它对自己拉流路径
            // （PT 11 静态映射）造成的采样率误标，那一半只能在真实摄像头上验证。
        String publishFilter = "aeval=exprs='" + CameraCompatibilityUtils.LE_SAMPLE_BYTESWAP + "':c=same";
            publisher = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-re", "-f", "lavfi", "-i", "color=c=blue:s=320x240:r=25",
                    "-re", "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=16000",
                    "-af", publishFilter,
                    "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-g", "25", "-c:a", "pcm_s16be", "-ac", "1",
                    "-f", "rtsp", "-rtsp_transport", "tcp", base + "/pcm-le")
                    .redirectError(publishLog.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Thread.sleep(1200);
            encoder = new ProcessBuilder(CameraCompatibilityUtils.command("ffmpeg", base + "/pcm-le", base + "/opus-le", true, true, 16000))
                    .redirectError(encodeLog.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Thread.sleep(5000);
            assertTrue(encoder.isAlive(), () -> read(encodeLog));
            Path pcm = directory.resolve("decoded-le.pcm");
            reader = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-rtsp_transport", "tcp", "-i", base + "/opus-le",
                    "-map", "0:a:0", "-t", "3", "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "1", "-f", "s16le", pcm.toString())
                    .redirectError(readLog.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            assertTrue(reader.waitFor(25, TimeUnit.SECONDS)); assertEquals(0, reader.exitValue(), () -> read(readLog));
            var samples = ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            assertTrue(samples.limit() > 48000);
            double sum = 0, maxJump = 0, previous = 0; int crossings = 0, count = 0;
            for (int i = 4800; i < samples.limit() - 4800; i++) {
                double value = samples.get(i) / 32768.0;
                if (count > 0) { maxJump = Math.max(maxJump, Math.abs(value - previous)); if (previous <= 0 && value > 0) crossings++; }
                previous = value; sum += value * value; count++;
            }
            double rms = Math.sqrt(sum / count), frequency = crossings * 48000.0 / count;
            assertTrue(rms > .04 && rms < .15, "Unexpected amplitude " + rms + " (byte order not restored?)");
            assertEquals(1000, frequency, 5); assertTrue(maxJump < .08, "Audio discontinuity " + maxJump);
            System.out.printf("VERIFIED little-endian PCM -> Opus48k -> PCM: RMS=%.5f, frequency=%.2f, maximumStep=%.5f%n", rms, frequency, maxJump);
        } finally { stop(reader, encoder, publisher, server); }
    }
    @ParameterizedTest
    @ValueSource(ints = {8000, 44100, 48000})
    void actualPcmRtspToOpusRemainsContinuous(int rate) throws Exception {
        String binary = System.getenv("CAMERA_TEST_MEDIAMTX");
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(Path.of(binary)), "Set CAMERA_TEST_MEDIAMTX for isolated PCM test");
        int port;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        Path conf = directory.resolve("mediamtx.yml");
        Files.writeString(conf, mediamtxConfig(port));
        Path serverLog = pipeLog("server"), publishLog = pipeLog("publish"),
                encodeLog = pipeLog("encode"), readLog = pipeLog("read");
        Process server = new ProcessBuilder(binary, conf.toString()).redirectErrorStream(true).redirectOutput(serverLog.toFile()).start();
        Process publisher = null, encoder = null, reader = null;
        try {
            waitForRtsp(server, port, serverLog);
            String base = "rtsp://127.0.0.1:" + port;
            publisher = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-re", "-f", "lavfi", "-i", "color=c=blue:s=320x240:r=25",
                    "-re", "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=" + rate,
                    "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-g", "25", "-c:a", "pcm_s16be", "-ac", "1",
                    "-f", "rtsp", "-rtsp_transport", "tcp", base + "/pcm")
                    .redirectError(publishLog.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Thread.sleep(1200);
            encoder = new ProcessBuilder(command("ffmpeg", base + "/pcm", base + "/opus", true))
                    .redirectError(encodeLog.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Thread.sleep(5000);
            assertTrue(encoder.isAlive(), () -> read(encodeLog));
            Path pcm = directory.resolve("decoded.pcm");
            reader = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-rtsp_transport", "tcp", "-i", base + "/opus",
                    "-map", "0:a:0", "-t", "3", "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "1", "-f", "s16le", pcm.toString())
                    .redirectError(readLog.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            assertTrue(reader.waitFor(25, TimeUnit.SECONDS)); assertEquals(0, reader.exitValue(), () -> read(readLog));
            var samples = ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            assertTrue(samples.limit() > 48000);
            double sum = 0, maxJump = 0, previous = 0; int crossings = 0, count = 0;
            for (int i = 4800; i < samples.limit() - 4800; i++) {
                double value = samples.get(i) / 32768.0;
                if (count > 0) { maxJump = Math.max(maxJump, Math.abs(value - previous)); if (previous <= 0 && value > 0) crossings++; }
                previous = value; sum += value * value; count++;
            }
            double rms = Math.sqrt(sum / count), frequency = crossings * 48000.0 / count;
            assertTrue(rms > .04 && rms < .15, "Unexpected amplitude " + rms);
            assertEquals(1000, frequency, 5); assertTrue(maxJump < .08, "Audio discontinuity " + maxJump);
            System.out.printf("VERIFIED PCM %dHz -> Opus48k -> PCM: RMS=%.5f, frequency=%.2f, maximumStep=%.5f%n", rate, rms, frequency, maxJump);
        } finally { stop(reader, encoder, publisher, server); }
    }
    private static List<String> command(String ffmpeg, String input, String output, boolean pcm) {
        return CameraCompatibilityUtils.command(ffmpeg, input, output, pcm);
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception e) { return "missing diagnostic log"; } }
}
