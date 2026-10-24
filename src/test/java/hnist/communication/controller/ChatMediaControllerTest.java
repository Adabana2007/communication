package hnist.communication.controller;

import hnist.communication.exception.BusinessException;
import hnist.communication.common.Result;
import hnist.communication.service.impl.ChatMediaServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMediaControllerTest {

    private static final String MEDIA_ID = "1700000000000-1";
    private static final byte[] CONTENT = "0123456789".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    private ChatMediaController controller;
    private Path chatDirectory;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ChatMediaController(new ChatMediaServiceImpl(directory.toString(), ""));
        chatDirectory = Files.createDirectories(directory.resolve("chat"));
        Files.write(chatDirectory.resolve(MEDIA_ID + ".mp4"), CONTENT);
    }

    @Test
    void servesWholeMediaAndSupportsRange() throws Exception {
        ResponseEntity<InputStreamResource> whole = controller.media(MEDIA_ID, null);
        assertEquals(HttpStatus.OK, whole.getStatusCode());
        assertEquals(10, whole.getHeaders().getContentLength());
        assertEquals("bytes", whole.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        try (InputStream input = whole.getBody().getInputStream()) {
            assertArrayEquals(CONTENT, input.readAllBytes());
        }

        ResponseEntity<InputStreamResource> partial = controller.media(MEDIA_ID, "bytes=1-3");
        assertEquals(HttpStatus.PARTIAL_CONTENT, partial.getStatusCode());
        assertEquals("bytes 1-3/10", partial.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        try (InputStream input = partial.getBody().getInputStream()) {
            assertArrayEquals("123".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
        }
    }

    @Test
    void returns416ForInvalidRangeAnd400ForUnsafeId() {
        ResponseEntity<InputStreamResource> invalidRange = controller.media(MEDIA_ID, "bytes=-0");
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, invalidRange.getStatusCode());
        assertEquals("bytes */10", invalidRange.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));

        assertEquals(HttpStatus.BAD_REQUEST,
                controller.media("../../etc/passwd", null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                controller.media("99999-1", null).getStatusCode());
    }

    @Test
    void uploadsWhitelistedImageAndReturnsAccessibleUrls() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02};
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", png);

        Result<Map<String, Object>> result = controller.upload(file, null);

        assertEquals(200, result.getCode());
        Map<String, Object> data = result.getData();
        assertNotNull(data);
        assertEquals("image", data.get("mediaType"));
        String mediaId = String.valueOf(data.get("mediaId"));
        assertEquals("/api/chat/media/" + mediaId, data.get("url"));
        assertEquals("/api/chat/media/" + mediaId + "/thumb", data.get("thumb"));
        assertTrue(Files.isRegularFile(chatDirectory.resolve(mediaId + ".png")));
    }

    @Test
    void rejectsFileWhoseMagicDoesNotMatchExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png",
                "not-an-image".getBytes(StandardCharsets.UTF_8));

        assertThrows(BusinessException.class, () -> controller.upload(file, null));
    }
}
