package hnist.communication.manager.camera;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 根据 MediaMTX 自报的 tracks2 判断轨道能否被 WebRTC（WHEP）直接播放；不支持时由播放准备启动兼容转码。
 * 无轨道数据时按可直连返回，避免因信息不足阻断播放。
 */
public final class WebrtcSupport {

    public record Decision(boolean supported, String reason) {}

    /** 浏览器 WebRTC 普遍可解的视频编码（MediaMTX codec 名，小写）。 */
    private static final Set<String> VIDEO_SUPPORTED = Set.of("h264", "av1", "vp8", "vp9");
    /** WebRTC 支持的音频封装（无需转码即可直通）。 */
    private static final Set<String> AUDIO_PASSTHROUGH = Set.of("opus", "pcma", "pcmu", "g711a", "g711u", "g711", "g722");
    /** 已知但默认不直通的音频编码；只有 LPCM 48kHz/16bit 例外，其余需要转码。 */
    private static final Set<String> AUDIO_KNOWN = Set.of("mpeg4audio", "aac", "mp2", "mp3", "mpeg1audio", "mpeg2audio", "lpcm", "pcm");

    private WebrtcSupport() {}

    public static Decision assess(List<CameraMediaGateway.TrackInfo> tracks) {
        if (tracks == null || tracks.isEmpty()) return new Decision(true, "");
        String videoProblem = null, audioProblem = null, unknownProblem = null;
        for (CameraMediaGateway.TrackInfo track : tracks) {
            String codec = track.codec() == null ? "" : track.codec().trim().toLowerCase(Locale.ROOT);
            if (codec.isEmpty()) continue;
            if (isVideoCodec(codec)) {
                if (videoProblem == null && !VIDEO_SUPPORTED.contains(codec)) {
                    videoProblem = describe(codec);
                }
            } else if (isAudioCodec(codec)) {
                if (audioProblem == null && !audioSupported(track, codec)) {
                    audioProblem = describeAudio(track, codec);
                }
            } else if (unknownProblem == null) {
                // 未知编码保守处理：转码仍能出画，直连可能整场失败。
                unknownProblem = "轨道编码 " + track.codec() + " 无法确认被 WebRTC 支持";
            }
        }
        if (videoProblem != null) return new Decision(false, "视频" + videoProblem + "不被 WebRTC 支持");
        if (audioProblem != null) return new Decision(false, "音频" + audioProblem + "不被 WebRTC 支持");
        if (unknownProblem != null) return new Decision(false, unknownProblem);
        return new Decision(true, "");
    }

    private static boolean isVideoCodec(String codec) {
        return VIDEO_SUPPORTED.contains(codec) || codec.contains("265") || codec.contains("hevc") || codec.contains("264");
    }

    private static boolean isAudioCodec(String codec) {
        return AUDIO_PASSTHROUGH.contains(codec) || AUDIO_KNOWN.contains(codec);
    }

    /** LPCM 仅在 48kHz/16bit（WebRTC L16）时可直接直通，其余形态打包失败。 */
    private static boolean audioSupported(CameraMediaGateway.TrackInfo track, String codec) {
        if (AUDIO_PASSTHROUGH.contains(codec)) return true;
        if ("lpcm".equals(codec)) {
            return (track.sampleRate() == null || track.sampleRate() == 48000)
                    && (track.bitDepth() == null || track.bitDepth() == 16);
        }
        return false;
    }

    private static String describe(String codec) {
        if (codec.contains("265") || codec.contains("hevc")) return "H.265 编码";
        return codec.toUpperCase(Locale.ROOT) + " 编码";
    }

    private static String describeAudio(CameraMediaGateway.TrackInfo track, String codec) {
        String name;
        if ("lpcm".equals(codec)) {
            name = "PCM";
        } else if ("mpeg4audio".equals(codec) || "aac".equals(codec)) {
            name = "AAC";
        } else {
            name = track.codec();
        }
        return track.sampleRate() != null
                ? name + "（" + track.sampleRate() + "Hz）"
                : name;
    }
}
