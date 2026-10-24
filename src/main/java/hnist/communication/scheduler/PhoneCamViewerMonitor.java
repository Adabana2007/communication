package hnist.communication.scheduler;

import hnist.communication.service.PhoneCameraService;
import hnist.communication.websocket.PhoneCamWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 定时检查查看者心跳，超时（60s）即停止推流并通知手机返回上一页。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhoneCamViewerMonitor {

    private final PhoneCameraService phoneCameraService;
    private final PhoneCamWebSocketHandler wsHandler;

    @Scheduled(fixedRate = 10_000)
    public void checkStaleViewers() {
        try {
            List<String> stoppedDevices = phoneCameraService.stopStaleStreams();
            for (String deviceId : stoppedDevices) {
                log.info("[PhoneCam] 查看者超时退出，自动停止推流 deviceId={}", deviceId);
                wsHandler.sendToDevice(deviceId, Map.of("type", "stop_stream"));
            }
        } catch (Exception e) {
            // 单次检查失败只记日志，不能中断后续定时轮次。
            log.warn("[PhoneCam] 查看者超时检查失败: {}", e.getMessage());
        }
    }
}
