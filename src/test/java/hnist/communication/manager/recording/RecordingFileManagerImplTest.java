package hnist.communication.manager.recording;

import hnist.communication.exception.BusinessException;
import hnist.communication.manager.recording.impl.RecordingFileManagerImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordingFileManagerImplTest {

    @TempDir
    Path directory;

    @Test
    void resolvesExistingFileInsideRecordingRoot() throws Exception {
        Path recording = Files.writeString(directory.resolve("call.mp4"), "recording");
        RecordingFileManager manager = new RecordingFileManagerImpl(directory.toString());

        Resource resource = manager.resolve("call.mp4");

        assertEquals("call.mp4", resource.getFilename());
        assertEquals(recording.toFile().getCanonicalPath(), resource.getFile().getCanonicalPath());
    }

    @Test
    void rejectsTraversalAndMissingFilesWithOriginalMessages() {
        RecordingFileManager manager = new RecordingFileManagerImpl(directory.toString());

        BusinessException traversal = assertThrows(BusinessException.class, () -> manager.resolve("../secret.mp4"));
        assertEquals("录制文件路径无效", traversal.getMessage());

        BusinessException missing = assertThrows(BusinessException.class, () -> manager.resolve("missing.mp4"));
        assertEquals("录制文件不存在", missing.getMessage());

        BusinessException empty = assertThrows(BusinessException.class, () -> manager.resolve(""));
        assertEquals("该通话无录制文件", empty.getMessage());
    }
}
