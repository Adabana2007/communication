package hnist.communication.service;

import hnist.communication.entity.Device;

import java.util.List;

/** 设备档案的增删改查。 */
public interface DeviceService {

    List<Device> list();

    Device get(Long id);

    Device create(Device device);

    Device update(Long id, Device device);

    void delete(Long id);
}
