package hnist.communication.manager.camera;

import hnist.communication.entity.Camera;

import java.util.ArrayList;
import java.util.List;

/** FFmpeg 兼容输出命令与滤镜构造工具。 */
public final class CameraCompatibilityUtils {

    /** 16bit 小端样本按大端解释时的逐样本字节交换表达式。 */
    public static final String LE_SAMPLE_BYTESWAP =
            "st(0,trunc(val(0)*32768));"
            + "st(1,(ld(0)-256*floor(ld(0)/256))*256+floor(ld(0)/256)-256*floor(ld(0)/65536));"
            + "(ld(1)-if(gt(ld(1),32767),65536,0))/32768";

    private CameraCompatibilityUtils() {
    }

    public static String compatiblePath(Camera camera) {
        return "cam_" + camera.getId() + "_compatible";
    }

    public static List<String> command(String ffmpeg, String input, String output) {
        return command(ffmpeg, input, output, false);
    }

    public static List<String> command(String ffmpeg, String input, String output, boolean pcm) {
        return command(ffmpeg, input, output, pcm, false, 0);
    }

    public static List<String> command(String ffmpeg, String input, String output, boolean pcm,
                                       boolean pcmLittleEndian, int pcmSampleRate) {
        List<String> args = new ArrayList<>(List.of(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
                "-rtsp_transport", "tcp", "-i", input,
                "-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
                "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-bf", "0", "-crf", "23",
                "-vf", "fps=30,scale=trunc(iw/2)*2:trunc(ih/2)*2", "-g", "30",
                "-c:a", "libopus", "-b:a", "64k", "-ar", "48000", "-ac", "1"));
        if (pcm) {
            args.addAll(List.of("-af", pcmFilters(pcmLittleEndian, pcmSampleRate)));
        }
        args.addAll(List.of("-f", "rtsp", "-rtsp_transport", "tcp", output));
        return List.copyOf(args);
    }

    public static String pcmFilters(boolean pcmLittleEndian, int pcmSampleRate) {
        if (!pcmLittleEndian) {
            return "aresample=48000:async=100:min_hard_comp=0.1";
        }
        List<String> filters = new ArrayList<>();
        filters.add("aeval=exprs='" + LE_SAMPLE_BYTESWAP + "':c=same");
        if (pcmSampleRate > 0) {
            filters.add("asetrate=" + pcmSampleRate);
            filters.add("aresample=48000");
            filters.add("asetpts=N/SR/TB");
        } else {
            filters.add("aresample=48000:async=100:min_hard_comp=0.1");
        }
        return String.join(",", filters);
    }
}
