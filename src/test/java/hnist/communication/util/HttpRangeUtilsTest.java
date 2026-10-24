package hnist.communication.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HttpRangeUtilsTest {

    @Test
    void parsesBoundedRangeInclusively() {
        HttpRangeUtils.ByteRange range = HttpRangeUtils.parseSingleRange("bytes=0-0", 10);
        assertEquals(0, range.start());
        assertEquals(0, range.end());
        assertEquals(1, range.length());
    }

    @Test
    void parsesOpenEndedRangeAndClipsPastEnd() {
        assertEquals(new HttpRangeUtils.ByteRange(5, 9),
                HttpRangeUtils.parseSingleRange("bytes=5-", 10));
        assertEquals(new HttpRangeUtils.ByteRange(3, 9),
                HttpRangeUtils.parseSingleRange("bytes=3-999", 10));
    }

    @Test
    void parsesSuffixRange() {
        assertEquals(new HttpRangeUtils.ByteRange(5, 9),
                HttpRangeUtils.parseSingleRange("bytes=-5", 10));
        assertEquals(new HttpRangeUtils.ByteRange(0, 9),
                HttpRangeUtils.parseSingleRange("bytes=-999", 10));
    }

    @Test
    void unitIsCaseInsensitiveAndWhitespaceIsTolerated() {
        assertEquals(new HttpRangeUtils.ByteRange(1, 2),
                HttpRangeUtils.parseSingleRange("BYTES=1-2", 10));
        assertEquals(new HttpRangeUtils.ByteRange(1, 2),
                HttpRangeUtils.parseSingleRange("bytes= 1 - 2 ", 10));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0-1", "bytes=", "bytes=-", "bytes=1", "bytes=1-2-3",
            "bytes=a-b", "bytes=+1-2", "bytes=1.0-2", "bytes=0-1,4-5",
            "bytes=10-", "bytes=11-12", "bytes=3-2", "bytes=-0", "bytes=--1"
    })
    void rejectsMalformedOrUnsatisfiableRanges(String header) {
        assertNull(HttpRangeUtils.parseSingleRange(header, 10));
    }

    @Test
    void rejectsRangeForEmptyOrMissingInput() {
        assertNull(HttpRangeUtils.parseSingleRange(null, 10));
        assertNull(HttpRangeUtils.parseSingleRange("bytes=0-0", 0));
        assertNull(HttpRangeUtils.parseSingleRange("bytes=0-0", -1));
    }
}
