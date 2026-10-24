package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.dto.CameraRequest;
import hnist.communication.service.AccessControlService;
import hnist.communication.service.CameraService;
import hnist.communication.vo.CameraVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

/** 监控摄像头的增删改查与推流控制。 */
@RestController
@RequestMapping("/api/cameras")
@RequiredArgsConstructor
public class CameraController {

    private final CameraService cameraService;
    private final hnist.communication.manager.camera.CameraDiagnostics diagnostics;
    private final AccessControlService accessControl;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<CameraVO> add(@Valid @RequestBody CameraRequest req) {
        return Result.success(cameraService.addCamera(req));
    }

    @GetMapping
    public Result<List<CameraVO>> list(Authentication authentication) {
        List<CameraVO> cameras = cameraService.listCameras().stream()
                .map(camera -> maskForViewer(camera, authentication))
                .toList();
        return Result.success(cameras);
    }

    @GetMapping("/{id}")
    public Result<CameraVO> get(@PathVariable Long id, Authentication authentication) {
        return Result.success(maskForViewer(cameraService.getCamera(id), authentication));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<CameraVO> update(@PathVariable Long id, @Valid @RequestBody CameraRequest req) {
        return Result.success(cameraService.updateCamera(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        cameraService.deleteCamera(id);
        return Result.success();
    }

    @PostMapping("/{id}/play")
    public Result<CameraVO> play(@PathVariable Long id, Authentication authentication) {
        return Result.success(maskForViewer(cameraService.preparePlayback(id), authentication));
    }

    @PostMapping("/{id}/diagnose")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<hnist.communication.manager.camera.CameraDiagnostics.Report> diagnose(@PathVariable Long id) {
        return Result.success(diagnostics.start(id));
    }

    @PostMapping("/{id}/reconnect")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<CameraVO> reconnect(@PathVariable Long id) {
        return Result.success(cameraService.reconnectStream(id));
    }

    /** 刷新流状态（只读，不中断现有观看者） */
    @PostMapping("/{id}/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<CameraVO> refresh(@PathVariable Long id) {
        return Result.success(cameraService.refreshStream(id));
    }

    private CameraVO maskForViewer(CameraVO camera, Authentication authentication) {
        if (!accessControl.hasAdminRole(authentication)) {
            camera.maskCredentials();
        }
        return camera;
    }
}
