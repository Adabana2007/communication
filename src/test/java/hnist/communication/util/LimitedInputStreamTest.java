package hnist.communication.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LimitedInputStreamTest {

    @Test
    void neverReadsPastTheRequestedBoundary() throws Exception {
        try (LimitedInputStream input = new LimitedInputStream(
                new ByteArrayInputStream("abcdefghij".getBytes(StandardCharsets.UTF_8)), 4)) {
            assertEquals(4, input.available());
            assertArrayEquals("abcd".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
        }
    }

    @Test
    void honorsSingleByteAndSkipLimits() throws Exception {
        try (LimitedInputStream input = new LimitedInputStream(
                new ByteArrayInputStream("abcdefghij".getBytes(StandardCharsets.UTF_8)), 5)) {
            assertEquals('a', input.read());
            assertEquals(2, input.skip(2));
            assertEquals('d', input.read());
            assertEquals('e', input.read());
            assertEquals(-1, input.read());
        }
    }

    @Test
    void rejectsNegativeLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new LimitedInputStream(new ByteArrayInputStream(new byte[0]), -1));
    }
}
