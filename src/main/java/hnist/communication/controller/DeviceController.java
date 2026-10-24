package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.entity.Device;
import hnist.communication.service.AccessControlService;
import hnist.communication.service.DeviceService;
import hnist.communication.util.SensitiveDataMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 设备基础信息维护。 */
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;
    private final AccessControlService accessControl;

    @GetMapping
    public Result<List<Device>> list(Authentication authentication) {
        List<Device> devices = deviceService.list();
        devices.forEach(device -> maskSecret(device, authentication));
        return Result.success(devices);
    }

    @GetMapping("/{id}")
    public Result<Device> get(@PathVariable Long id, Authentication authentication) {
        Device device = deviceService.get(id);
        maskSecret(device, authentication);
        return Result.success(device);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Device> create(@RequestBody Device device) {
        return Result.success(deviceService.create(device));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Device> update(@PathVariable Long id, @RequestBody Device device) {
        return Result.success(deviceService.update(id, device));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        deviceService.delete(id);
        return Result.success();
    }

    private void maskSecret(Device device, Authentication authentication) {
        if (!accessControl.hasAdminRole(authentication)) {
            device.setWifiPassword(SensitiveDataMasker.maskSecret(device.getWifiPassword()));
        }
    }
}
