package hnist.communication.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaFileValidatorTest {

    @Test
    void acceptsKnownImageAndContainerHeaders() {
        assertTrue(MediaFileValidator.matches("JPG", bytes(0xFF, 0xD8, 0xFF, 0xE0)));
        assertTrue(MediaFileValidator.matches("png", bytes(0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A)));
        assertTrue(MediaFileValidator.matches("gif", ascii("GIF89a")));
        assertTrue(MediaFileValidator.matches("webp", riff("WEBP")));
        assertTrue(MediaFileValidator.matches("wav", riff("WAVE")));
        assertTrue(MediaFileValidator.matches("mkv", bytes(0x1A, 0x45, 0xDF, 0xA3)));
    }

    @Test
    void recognizesIsoBmffBrands() {
        assertTrue(MediaFileValidator.matches("mp4", ftyp("isom")));
        assertTrue(MediaFileValidator.matches("mov", ftyp("qt  ")));
        assertTrue(MediaFileValidator.matches("3gp", ftyp("3gp4")));
        assertTrue(MediaFileValidator.matches("m4a", ftyp("M4A ")));
        assertEquals("mp4", MediaFileValidator.detectExtension(ftyp("mp42")));
        assertEquals("mov", MediaFileValidator.detectExtension(ftyp("qt  ")));
        assertEquals("3gp", MediaFileValidator.detectExtension(ftyp("3gp5")));
        assertEquals("m4a", MediaFileValidator.detectExtension(ftyp("M4A ")));
    }

    @Test
    void distinguishesAdtsAacFromMpegAudio() {
        byte[] aac = bytes(0xFF, 0xF1, 0x50, 0x80);
        byte[] mp3 = bytes(0xFF, 0xFB, 0x90, 0x64);
        assertTrue(MediaFileValidator.matches("aac", aac));
        assertFalse(MediaFileValidator.matches("mp3", aac));
        assertEquals("aac", MediaFileValidator.detectExtension(aac));
        assertTrue(MediaFileValidator.matches("mp3", mp3));
        assertEquals("mp3", MediaFileValidator.detectExtension(mp3));
        assertTrue(MediaFileValidator.matches("mp3", ascii("ID3")));
    }

    @Test
    void acceptsAmrAndRejectsMismatches() {
        assertTrue(MediaFileValidator.matches("amr", ascii("#!AMR\n")));
        assertFalse(MediaFileValidator.matches("png", ascii("GIF89a")));
        assertFalse(MediaFileValidator.matches("exe", ascii("MZ")));
        assertFalse(MediaFileValidator.matches(null, ascii("GIF89a")));
        assertFalse(MediaFileValidator.matches("gif", null));
        assertEquals("", MediaFileValidator.detectExtension(ascii("unknown")));
    }

    private static byte[] riff(String type) {
        byte[] header = new byte[12];
        System.arraycopy(ascii("RIFF"), 0, header, 0, 4);
        System.arraycopy(ascii(type), 0, header, 8, 4);
        return header;
    }

    private static byte[] ftyp(String brand) {
        byte[] header = new byte[12];
        System.arraycopy(ascii("ftyp"), 0, header, 4, 4);
        System.arraycopy(ascii(brand), 0, header, 8, 4);
        return header;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
