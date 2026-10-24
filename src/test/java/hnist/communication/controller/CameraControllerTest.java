package hnist.communication.controller;

import hnist.communication.manager.camera.CameraDiagnostics;
import hnist.communication.service.AccessControlService;
import hnist.communication.service.CameraService;
import hnist.communication.vo.CameraVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CameraControllerTest {

    private CameraService cameraService;
    private AccessControlService accessControl;
    private CameraController controller;

    @BeforeEach
    void setUp() {
        cameraService = mock(CameraService.class);
        accessControl = mock(AccessControlService.class);
        controller = new CameraController(cameraService, mock(CameraDiagnostics.class), accessControl);
    }

    @Test
    void masksCameraCredentialsForRegularUsersOnEveryReadEndpoint() {
        CameraVO camera = sensitiveCamera();
        Authentication user = authentication("ROLE_USER");
        when(cameraService.listCameras()).thenReturn(List.of(camera));
        when(cameraService.getCamera(7L)).thenReturn(sensitiveCamera());
        when(cameraService.preparePlayback(7L)).thenReturn(sensitiveCamera());

        assertMasked(controller.list(user).getData().getFirst());
        assertMasked(controller.get(7L, user).getData());
        assertMasked(controller.play(7L, user).getData());
    }

    @Test
    void keepsCameraCredentialsForAdministrators() {
        Authentication admin = authentication("ROLE_ADMIN");
        when(accessControl.hasAdminRole(admin)).thenReturn(true);
        when(cameraService.getCamera(7L)).thenReturn(sensitiveCamera());

        CameraVO camera = controller.get(7L, admin).getData();

        assertEquals("operator", camera.getUsername());
        assertEquals("rtsp://operator:secret@192.0.2.7/live?token=abc&channel=1", camera.getRtspUrl());
        assertEquals("http://192.0.2.7/snapshot?password=p@ss", camera.getSnapshotUrl());
    }

    private CameraVO sensitiveCamera() {
        return CameraVO.builder()
                .id(7L)
                .username("operator")
                .rtspUrl("rtsp://operator:secret@192.0.2.7/live?token=abc&channel=1")
                .snapshotUrl("http://192.0.2.7/snapshot?password=p@ss")
                .build();
    }

    private Authentication authentication(String authority) {
        return new UsernamePasswordAuthenticationToken(
                42L, null, List.of(new SimpleGrantedAuthority(authority)));
    }

    private void assertMasked(CameraVO camera) {
        assertEquals("******", camera.getUsername());
        assertEquals("rtsp://******@192.0.2.7/live?token=******&channel=1", camera.getRtspUrl());
        assertEquals("http://192.0.2.7/snapshot?password=******", camera.getSnapshotUrl());
    }
}
