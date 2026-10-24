package hnist.communication.service;

import hnist.communication.exception.BusinessException;
import hnist.communication.service.impl.VideoServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VideoServiceImplTest {

    private static final byte[] CONTENT = "0123456789".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    private VideoServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        Files.write(directory.resolve("sample.mp4"), CONTENT);
        service = new VideoServiceImpl(directory.toString());
    }

    @Test
    void streamsWholeFileWithoutRange() {
        ResponseEntity<Resource> response = service.streamVideo("sample.mp4", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10, response.getHeaders().getContentLength());
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
    }

    @Test
    void streamsRequestedSliceAsPartialContent() throws Exception {
        ResponseEntity<Resource> response = service.streamVideo("sample.mp4", "bytes=2-5");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 2-5/10", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(4, response.getHeaders().getContentLength());
        try (InputStream input = response.getBody().getInputStream()) {
            assertArrayEquals("2345".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
        }
    }

    @Test
    void rejectsUnsatisfiableOrMalformedRangeWith416() {
        ResponseEntity<Resource> outOfBounds = service.streamVideo("sample.mp4", "bytes=99-100");
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, outOfBounds.getStatusCode());
        assertEquals("bytes */10", outOfBounds.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertNull(outOfBounds.getBody());

        ResponseEntity<Resource> multiRange = service.streamVideo("sample.mp4", "bytes=0-1,4-5");
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, multiRange.getStatusCode());
    }

    @Test
    void refusesMissingFileAndPathTraversal() {
        assertThrows(BusinessException.class, () -> service.streamVideo("missing.mp4", null));
        assertThrows(BusinessException.class, () -> service.streamVideo("../sample.mp4", null));
    }
}
