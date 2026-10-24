package hnist.communication.manager.camera;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** WebrtcSupport 判定表：支持→直连，不支持→按需转码。 */
class WebrtcSupportTest {

    private static CameraMediaGateway.TrackInfo t(String codec, Integer sampleRate, Integer bitDepth) {
        return new CameraMediaGateway.TrackInfo(codec, sampleRate, bitDepth);
    }

    @Test
    void 空轨道信息_按可直连处理_不改变现状() {
        assertTrue(WebrtcSupport.assess(null).supported());
        assertTrue(WebrtcSupport.assess(List.of()).supported());
    }

    @Test
    void h264加g711_直连() {
        var d = WebrtcSupport.assess(List.of(t("H264", null, null), t("PCMA", 8000, null)));
        assertTrue(d.supported(), d.reason());
    }

    @Test
    void h264加opus_直连() {
        var d = WebrtcSupport.assess(List.of(t("H264", null, null), t("Opus", 48000, null)));
        assertTrue(d.supported(), d.reason());
    }

    @Test
    void h264加48k16bit_lpcm_直连() {
        var d = WebrtcSupport.assess(List.of(t("H264", null, null), t("LPCM", 48000, 16)));
        assertTrue(d.supported(), d.reason());
    }

    @Test
    void h264加44100_pcm_转码_并说明原因() {
        var d = WebrtcSupport.assess(List.of(t("H264", null, null), t("LPCM", 44100, 16)));
        assertFalse(d.supported());
        assertTrue(d.reason().contains("PCM"), d.reason());
        assertTrue(d.reason().contains("44100"), d.reason());
    }

    @Test
    void lpcm非16bit_转码() {
        var d = WebrtcSupport.assess(List.of(t("LPCM", 48000, 24)));
        assertFalse(d.supported());
    }

    @Test
    void aac音频_转码() {
        var d = WebrtcSupport.assess(List.of(t("H264", null, null), t("MPEG4Audio", 44100, null)));
        assertFalse(d.supported());
        assertTrue(d.reason().contains("AAC"), d.reason());
    }

    @Test
    void h265视频_转码() {
        var d = WebrtcSupport.assess(List.of(t("H265", null, null), t("Opus", 48000, null)));
        assertFalse(d.supported());
        assertTrue(d.reason().contains("H.265"), d.reason());
    }

    @Test
    void 纯视频无音轨_h264_直连() {
        assertTrue(WebrtcSupport.assess(List.of(t("H264", null, null))).supported());
    }

    @Test
    void 未知编码_保守转码() {
        var d = WebrtcSupport.assess(List.of(t("MotionJpeg", null, null)));
        assertFalse(d.supported());
    }

    @Test
    void 视频问题优先于音频问题呈现() {
        var d = WebrtcSupport.assess(List.of(t("H265", null, null), t("LPCM", 44100, 16)));
        assertFalse(d.supported());
        assertTrue(d.reason().contains("H.265"), d.reason());
    }
}
