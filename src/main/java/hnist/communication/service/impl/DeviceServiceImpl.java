package hnist.communication.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import hnist.communication.exception.BusinessException;
import hnist.communication.entity.Device;
import hnist.communication.mapper.DeviceMapper;
import hnist.communication.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 设备网络与无线参数配置的增删改查。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceMapper deviceMapper;

    @Override
    public List<Device> list() {
        return deviceMapper.selectList(
                new LambdaQueryWrapper<Device>().orderByAsc(Device::getId));
    }

    @Override
    public Device get(Long id) {
        Device device = deviceMapper.selectById(id);
        if (device == null) throw new BusinessException("设备不存在");
        return device;
    }

    @Override
    @Transactional
    public Device create(Device device) {
        deviceMapper.insert(device);
        return device;
    }

    @Override
    @Transactional
    public Device update(Long id, Device req) {
        Device device = get(id);
        // 只更新非 null 字段，防止前端未传的字段被覆盖为 null
        if (req.getName() != null) device.setName(req.getName());
        if (req.getShortName() != null) device.setShortName(req.getShortName());
        if (req.getNodeAddr() != null) device.setNodeAddr(req.getNodeAddr());
        if (req.getNodeMac() != null) device.setNodeMac(req.getNodeMac());
        if (req.getNodeFreq() != null) device.setNodeFreq(req.getNodeFreq());
        if (req.getNodeBw() != null) device.setNodeBw(req.getNodeBw());
        if (req.getNodePower() != null) device.setNodePower(req.getNodePower());
        if (req.getDistance() != null) device.setDistance(req.getDistance());
        if (req.getMeshId() != null) device.setMeshId(req.getMeshId());
        if (req.getAesLevel() != null) device.setAesLevel(req.getAesLevel());
        if (req.getDhcpEnabled() != null) device.setDhcpEnabled(req.getDhcpEnabled());
        if (req.getDhcpStart() != null) device.setDhcpStart(req.getDhcpStart());
        if (req.getDhcpEnd() != null) device.setDhcpEnd(req.getDhcpEnd());
        if (req.getDhcpMask() != null) device.setDhcpMask(req.getDhcpMask());
        if (req.getDhcpDns() != null) device.setDhcpDns(req.getDhcpDns());
        if (req.getDhcpGateway() != null) device.setDhcpGateway(req.getDhcpGateway());
        if (req.getWifiName() != null) device.setWifiName(req.getWifiName());
        if (req.getWifiPassword() != null) device.setWifiPassword(req.getWifiPassword());
        deviceMapper.updateById(device);
        return device;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        get(id);
        deviceMapper.deleteById(id);
    }
}
