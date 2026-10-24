package hnist.communication.util;

/** HTTP 单段 Range 解析工具，统一视频与聊天媒体的边界判断。 */
public final class HttpRangeUtils {

    private static final String RANGE_PREFIX = "bytes=";

    private HttpRangeUtils() {
    }

    /**
     * 解析 {@code bytes=start-end}、{@code bytes=start-} 或 {@code bytes=-suffix}。
     * 缺失、格式非法、多段或越界均返回 null，由调用方区分“未请求 Range”和“Range 不可满足”。
     */
    public static ByteRange parseSingleRange(String rangeHeader, long fileSize) {
        if (rangeHeader == null || fileSize <= 0
                || !rangeHeader.regionMatches(true, 0, RANGE_PREFIX, 0, RANGE_PREFIX.length())) {
            return null;
        }
        String spec = rangeHeader.substring(RANGE_PREFIX.length()).trim();
        if (spec.isEmpty() || spec.contains(",")) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.indexOf('-', dash + 1) >= 0) {
            return null;
        }
        try {
            String startText = spec.substring(0, dash).trim();
            String endText = spec.substring(dash + 1).trim();
            if (startText.isEmpty() && endText.isEmpty()) {
                return null;
            }
            long start;
            long end;
            if (startText.isEmpty()) {
                if (!isDigits(endText)) {
                    return null;
                }
                long suffixLength = Long.parseLong(endText);
                if (suffixLength <= 0) {
                    return null;
                }
                start = Math.max(0, fileSize - suffixLength);
                end = fileSize - 1;
            } else {
                if (!isDigits(startText) || (!endText.isEmpty() && !isDigits(endText))) {
                    return null;
                }
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? fileSize - 1 : Long.parseLong(endText);
            }
            if (start < 0 || start >= fileSize || start > end) {
                return null;
            }
            return new ByteRange(start, Math.min(end, fileSize - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current < '0' || current > '9') {
                return false;
            }
        }
        return true;
    }

    /** 左闭右闭的字节区间。 */
    public record ByteRange(long start, long end) {
        public long length() {
            return end - start + 1;
        }
    }
}
