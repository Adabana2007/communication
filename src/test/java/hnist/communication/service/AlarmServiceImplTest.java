package hnist.communication.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.Alarm;
import hnist.communication.entity.AlarmMedia;
import hnist.communication.mapper.AlarmMapper;
import hnist.communication.mapper.AlarmMediaMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.impl.AlarmServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlarmServiceImplTest {

    @Mock
    AlarmMapper alarmMapper;
    @Mock
    UserMapper userMapper;
    @Mock
    AlarmMediaMapper alarmMediaMapper;

    private AlarmServiceImpl service;

    @BeforeAll
    static void tableMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "alarm-unit-test");
        TableInfoHelper.initTableInfo(assistant, Alarm.class);
        TableInfoHelper.initTableInfo(assistant, AlarmMedia.class);
    }

    @BeforeEach
    void setUp() {
        service = new AlarmServiceImpl(alarmMapper, userMapper, alarmMediaMapper);
    }

    @Test
    void createForcesPendingAndClearsServerManagedFields() {
        doAnswer(invocation -> {
            Alarm alarm = invocation.getArgument(0);
            alarm.setId(42L);
            return 1;
        }).when(alarmMapper).insert(any(Alarm.class));
        Alarm request = Alarm.builder().userId(7L).status("done").handledBy(9L)
                .handleRemark("客户端伪造").build();

        Alarm saved = service.create(request);

        assertEquals(42L, saved.getId());
        assertEquals("pending", saved.getStatus());
        assertNull(saved.getHandledBy());
        assertNull(saved.getHandledAt());
        assertNull(saved.getHandleRemark());
        assertNotEquals(null, saved.getCreatedAt());
        verify(alarmMapper).insert(request);
    }

    @Test
    void bindsOwnedMediaAndDeduplicatesImageIds() {
        stubInsertedAlarm();
        when(alarmMediaMapper.update(isNull(), any())).thenReturn(1);

        service.createWithMedia(Alarm.builder().userId(7L).build(),
                Arrays.asList(11L, null, 11L), 12L);

        verify(alarmMediaMapper, times(2)).update(isNull(), any());
    }

    @Test
    void rejectsMediaCountOverLimitBeforeBinding() {
        stubInsertedAlarm();
        List<Long> tooMany = LongStream.rangeClosed(1, 21).boxed().toList();

        assertThrows(BusinessException.class,
                () -> service.createWithMedia(Alarm.builder().userId(7L).build(), tooMany, null));
        verify(alarmMediaMapper, never()).update(isNull(), any());
    }

    @Test
    void rejectsMediaThatIsNotOwnedMatchingOrUnclaimed() {
        stubInsertedAlarm();
        when(alarmMediaMapper.update(isNull(), any())).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.createWithMedia(
                Alarm.builder().userId(7L).build(), List.of(11L), null));
        assertThrows(BusinessException.class, () -> service.createWithMedia(
                Alarm.builder().userId(7L).build(), List.of(), 12L));
    }

    @Test
    void storesValidatedMediaWithUploaderOwnership() {
        when(alarmMediaMapper.insert(any(AlarmMedia.class))).thenReturn(1);
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        MockMultipartFile file = new MockMultipartFile("file", "alarm.jpg", "image/jpeg", jpeg);

        AlarmMedia media = service.storeMedia(file, 7L);

        assertEquals(7L, media.getUploaderId());
        assertEquals("image", media.getMediaType());
        assertEquals("image/jpeg", media.getMime());
        assertArrayEquals(jpeg, media.getData());
        verify(alarmMediaMapper).insert(media);
    }

    @Test
    void rejectsOversizedUnsupportedOrDisguisedMedia() {
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(16L * 1024 * 1024 + 1);
        assertThrows(BusinessException.class, () -> service.storeMedia(oversized, 7L));

        MockMultipartFile video = new MockMultipartFile("file", "a.mp4", "video/mp4",
                new byte[]{0, 0, 0, 0, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});
        assertThrows(BusinessException.class, () -> service.storeMedia(video, 7L));

        MockMultipartFile disguised = new MockMultipartFile("file", "fake.jpg", "image/jpeg",
                "not-an-image".getBytes(StandardCharsets.UTF_8));
        assertThrows(BusinessException.class, () -> service.storeMedia(disguised, 7L));
        verify(alarmMediaMapper, never()).insert(any(AlarmMedia.class));
    }

    private void stubInsertedAlarm() {
        doAnswer(invocation -> {
            Alarm alarm = invocation.getArgument(0);
            alarm.setId(42L);
            return 1;
        }).when(alarmMapper).insert(any(Alarm.class));
    }
}
