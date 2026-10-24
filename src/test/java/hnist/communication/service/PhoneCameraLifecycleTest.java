package hnist.communication.service;

import hnist.communication.entity.PhoneCamera;
import hnist.communication.mapper.PhoneCameraMapper;
import hnist.communication.mapper.UserMapper;
import hnist.communication.service.impl.PhoneCameraServiceImpl;
import hnist.communication.websocket.LocationWebSocketHandler;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhoneCameraLifecycleTest {
    PhoneCameraMapper mapper;
    MediaMtxService media;
    PhoneCameraServiceImpl service;
    PhoneCamera camera;

    @BeforeEach void setup() {
        mapper = mock(PhoneCameraMapper.class);
        media = mock(MediaMtxService.class);
        UserMapper users = mock(UserMapper.class);
        LocationWebSocketHandler location = mock(LocationWebSocketHandler.class);
        service = new PhoneCameraServiceImpl(mapper, users, media, location);
        camera = PhoneCamera.builder().id(7L).userId(3L).deviceId("test-phone").deviceName("测试手机")
                .status(PhoneCamera.STATUS_STREAMING).mediamtxPath("phone_testphone").hlsUrl("old-hls")
                .lastHeartbeat(LocalDateTime.now()).build();
        when(mapper.selectById(7L)).thenReturn(camera);
        when(mapper.selectOne(any())).thenReturn(camera);
        when(mapper.selectList(any())).thenReturn(List.of(camera));
        when(users.selectByIds(anyCollection())).thenReturn(List.of());
        when(location.getOnlineUserIds()).thenReturn(Set.of(3L));
        when(media.isSourceReady(anyString())).thenReturn(true);
        when(media.buildRtmpPushUrl(anyString())).thenReturn("rtmp://test/live/phone_testphone");
        when(media.buildWhipPushUrl(anyString())).thenReturn("http://test/phone_testphone/whip");
        when(media.buildHlsUrl(anyString())).thenReturn("http://test/phone_testphone/index.m3u8");
        when(media.buildWebrtcUrl(anyString())).thenReturn("http://test/phone_testphone");
    }

    @Test void phoneStopClearsPersistedUrlsAndListStatusImmediately() {
        assertEquals("streaming", service.getViewStatus(7L).get("state"));
        service.stopStreamByDevice("test-phone");
        assertEquals(PhoneCamera.STATUS_ONLINE, camera.getStatus());
        assertEquals("", camera.getMediamtxPath());
        assertEquals("", camera.getHlsUrl());
        assertEquals(PhoneCamera.STATUS_ONLINE, service.listAll().getFirst().getStatus());
        assertEquals("stopped", service.getViewStatus(7L).get("state"));
        assertEquals(false, service.getViewStatus(7L).get("ready"));
        verify(mapper).updateById(camera);
    }

    @Test void actualMybatisUpdateIncludesClearedPlaybackUrls() {
        service.stopStreamByDevice("test-phone");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(PhoneCameraMapper.class);
        String sql = configuration.getMappedStatement(PhoneCameraMapper.class.getName() + ".updateById")
                .getBoundSql(Map.of("et", camera)).getSql();
        assertTrue(sql.contains("mediamtx_path"), sql);
        assertTrue(sql.contains("hls_url"), sql);
    }

    @Test void lateReadyCannotReviveStoppedSharingEvenIfMediaIsStillClosing() {
        service.stopStream(7L);
        camera.setMediamtxPath("old-path-still-closing");
        service.confirmStreamReady("test-phone");
        assertEquals(PhoneCamera.STATUS_ONLINE, camera.getStatus());
        assertFalse(service.isStreamReady(7L));
        assertEquals("stopped", service.getViewStatus(7L).get("state"));
    }

    @Test void aNewViewCanStartAfterThePreviousShareStopped() {
        service.stopStream(7L);
        assertEquals("start_stream", service.prepareStream(7L).get("type"));
        service.confirmStreamReady("test-phone");
        assertEquals("streaming", service.getViewStatus(7L).get("state"));
        assertTrue(service.isStreamReady(7L));
    }

    @Test void rejectedAndFailedSharingKeepUsefulFailureReason() {
        service.reportStreamError("test-phone", "摄像头共享已中断");
        assertEquals("rejected", service.getViewStatus(7L).get("state"));
        assertEquals("摄像头共享已中断", service.getViewStatus(7L).get("error"));
        assertEquals("", camera.getMediamtxPath());
        assertEquals(PhoneCamera.STATUS_ONLINE, camera.getStatus());
    }

    @Test void disconnectIsOfflineAndAnUnknownDeviceDoesNotWaitForever() {
        service.markOffline("test-phone");
        assertEquals("offline", service.getViewStatus(7L).get("state"));
        assertEquals("", camera.getMediamtxPath());
        assertEquals("offline", service.getViewStatus(99L).get("state"));
    }

    @Test void refusalCannotBeOverwrittenByAnOldReadyMessage() {
        service.rejectStreamByDevice("test-phone");
        service.confirmStreamReady("test-phone");
        assertEquals("rejected", service.getViewStatus(7L).get("state"));
        assertEquals(PhoneCamera.STATUS_ONLINE, camera.getStatus());
        assertEquals("", camera.getHlsUrl());
    }
}
