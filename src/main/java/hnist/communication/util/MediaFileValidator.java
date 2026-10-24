package hnist.communication.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** 按文件头魔数校验上传内容，避免仅凭扩展名/MIME 放行伪装文件。 */
public final class MediaFileValidator {

    private static final Set<String> ISO_BMFF_BRANDS = Set.of(
            "isom", "iso2", "mp41", "mp42", "avc1", "dash", "M4V ",
            "qt  ", "M4A ", "3gp4", "3gp5", "3gp6", "3ggp");
    private static final byte[] EBML = {(byte) 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    private MediaFileValidator() {
    }

    /** 判断扩展名对应的文件头是否合法；未知扩展名一律拒绝。 */
    public static boolean matches(String extension, byte[] header) {
        if (extension == null || header == null) {
            return false;
        }
        String ext = extension.toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "jpg", "jpeg" -> startsWith(header, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "png" -> startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47,
                    0x0D, 0x0A, 0x1A, 0x0A});
            case "gif" -> asciiStartsWith(header, "GIF87a") || asciiStartsWith(header, "GIF89a");
            case "webp" -> asciiStartsWith(header, "RIFF") && asciiAt(header, 8, "WEBP");
            case "wav" -> asciiStartsWith(header, "RIFF") && asciiAt(header, 8, "WAVE");
            case "mp4", "3gp", "mov", "m4a" -> isIsoBmff(header);
            case "mkv" -> startsWith(header, EBML);
            case "mp3" -> asciiStartsWith(header, "ID3") || isMpegAudioFrame(header);
            case "aac" -> asciiStartsWith(header, "ID3") || isAdts(header);
            case "amr" -> asciiStartsWith(header, "#!AMR");
            default -> false;
        };
    }

    /** 根据文件头推断白名单扩展名；无法识别时返回空串。 */
    public static String detectExtension(byte[] header) {
        if (header == null || header.length < 2) {
            return "";
        }
        if (startsWith(header, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "jpg";
        }
        if (startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A})) {
            return "png";
        }
        if (asciiStartsWith(header, "GIF87a") || asciiStartsWith(header, "GIF89a")) {
            return "gif";
        }
        if (asciiStartsWith(header, "RIFF") && asciiAt(header, 8, "WEBP")) {
            return "webp";
        }
        if (asciiStartsWith(header, "RIFF") && asciiAt(header, 8, "WAVE")) {
            return "wav";
        }
        if (startsWith(header, EBML)) {
            return "mkv";
        }
        if (asciiStartsWith(header, "#!AMR")) {
            return "amr";
        }
        if (isAdts(header)) {
            return "aac";
        }
        // ADTS 与 MPEG 音频共用 11 位同步码，必须先排除 layer=00 的 ADTS，
        // 再识别真正的 MPEG 音频帧，避免 AAC 被误标成 MP3。
        if (asciiStartsWith(header, "ID3") || isMpegAudioFrame(header)) {
            return "mp3";
        }
        if (header.length >= 12 && asciiAt(header, 4, "ftyp")) {
            String brand = new String(header, 8, 4, StandardCharsets.ISO_8859_1);
            if ("qt  ".equals(brand)) {
                return "mov";
            }
            if (brand.startsWith("3g")) {
                return "3gp";
            }
            if ("M4A ".equals(brand)) {
                return "m4a";
            }
            if (ISO_BMFF_BRANDS.contains(brand)) {
                return "mp4";
            }
        }
        return "";
    }

    /** ISO BMFF（mp4/mov/3gp/m4a）在第 4~7 字节固定为 ftyp。 */
    private static boolean isIsoBmff(byte[] header) {
        if (header.length < 12 || !asciiAt(header, 4, "ftyp")) {
            return false;
        }
        String brand = new String(header, 8, 4, StandardCharsets.ISO_8859_1);
        return ISO_BMFF_BRANDS.contains(brand);
    }

    private static boolean isMpegAudioFrame(byte[] header) {
        return header.length >= 2 && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xE0) == 0xE0
                // MPEG Audio 的 Layer 位为 00 属于保留值，实际是 ADTS/AAC 等格式。
                && (header[1] & 0x06) != 0;
    }

    private static boolean isAdts(byte[] header) {
        return header.length >= 2 && (header[0] & 0xFF) == 0xFF
                && ((header[1] & 0xFF) == 0xF1 || (header[1] & 0xFF) == 0xF9);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length
                && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }

    private static boolean asciiStartsWith(byte[] value, String prefix) {
        return asciiAt(value, 0, prefix);
    }

    private static boolean asciiAt(byte[] value, int offset, String text) {
        byte[] expected = text.getBytes(StandardCharsets.ISO_8859_1);
        if (value.length < offset + expected.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOfRange(value, offset, offset + expected.length), expected);
    }
}
