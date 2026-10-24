package hnist.communication.manager.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CallRecordingMediaTest {
    @TempDir Path directory;
    private static final String FFMPEG = System.getenv().getOrDefault("CALL_RECORDING_TEST_FFMPEG", "ffmpeg");
    private static final String FFPROBE = System.getenv().getOrDefault("CALL_RECORDING_TEST_FFPROBE", "ffprobe");
    @Test void actualCaptureComposeDecodeAndLayoutSwitch() throws Exception {
        try { new ProcessBuilder(FFMPEG, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(); }
        catch (Exception e) { Assumptions.abort("FFmpeg not installed; set CALL_RECORDING_TEST_FFMPEG"); }
        fixture(directory.resolve("red.mp4"), "red", "1000"); fixture(directory.resolve("green.mp4"), "lime", "600");
        var config = new CallRecordingProperties(); config.setFfmpeg(FFMPEG); config.setFfprobe(FFPROBE); config.setComposeSeconds(120);
        var media = new CallRecordingMedia(config, new ObjectMapper());
        var job = new CallRecordingJob(); job.getLayouts().add(new CallRecordingJob.Layout(0, false, false, false));
        CountDownLatch progress = new CountDownLatch(1);
        Process process = media.capture(directory.resolve("red.mp4").toString(), directory.resolve("green.mp4").toString(), directory, time -> { if (time > 0) progress.countDown(); });
        try {
            assertTrue(progress.await(15, TimeUnit.SECONDS), () -> read(directory.resolve("capture.log")));
            job.setStartedAt(System.currentTimeMillis());
            Thread.sleep(1800); job.getLayouts().add(new CallRecordingJob.Layout(System.currentTimeMillis(), true, false, false));
            Thread.sleep(1800);
        } finally { media.stop(process); }
        System.out.println("CAPTURE local=" + media.probe(directory.resolve("local.mkv")) + " remote=" + media.probe(directory.resolve("remote.mkv")));
        CallRecordingMedia.Probe result;
        try { result = media.compose(job, directory); }
        catch (Exception e) { System.out.println(read(directory.resolve("compose.log"))); throw e; }
        assertEquals("h264", result.videoCodec()); assertEquals("aac", result.audioCodec());
        assertTrue(result.duration() > 3, result.toString()); assertTrue(result.duration() < 10, result.toString());
        assertEquals("green", pixel(directory.resolve("result.mp4"), .4));
        assertEquals("red", pixel(directory.resolve("result.mp4"), result.duration() - .4));
        assertTrue(Files.size(directory.resolve("result.mp4")) > 1024);
        System.out.println("VERIFIED actual server MP4: " + result + " file=" + directory.resolve("result.mp4"));
    }
    private void fixture(Path path, String color, String tone) throws Exception {
        Process p = new ProcessBuilder(FFMPEG, "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=" + color + ":s=640x480:r=30:d=12",
            "-f", "lavfi", "-i", "sine=frequency=" + tone + ":duration=12", "-c:v", "libx264", "-preset", "ultrafast", "-profile:v", "baseline",
            "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", path.toString()).redirectError(directory.resolve(color + ".log").toFile()).start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, p.exitValue());
    }
    private String pixel(Path video, double at) throws Exception {
        Process p = new ProcessBuilder(FFMPEG, "-v", "error", "-ss", String.valueOf(at), "-i", video.toString(), "-frames:v", "1",
            "-vf", "crop=2:2:100:100,format=rgb24", "-f", "rawvideo", "pipe:1").start();
        byte[] rgb = p.getInputStream().readAllBytes(); assertTrue(p.waitFor(10, TimeUnit.SECONDS)); assertTrue(rgb.length >= 3);
        return Byte.toUnsignedInt(rgb[0]) > Byte.toUnsignedInt(rgb[1]) ? "red" : "green";
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception e) { return "missing " + path; } }
}
