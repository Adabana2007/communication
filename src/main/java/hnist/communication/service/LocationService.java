package hnist.communication.service;

import hnist.communication.entity.LocationHistory;
import hnist.communication.entity.TrajectorySession;
import hnist.communication.vo.LocationVO;

import java.util.List;

/** 实时位置上报与轨迹会话管理。 */
public interface LocationService {
    void updateLocation(Long userId, Double latitude, Double longitude, String address,
                        Double speed, Double direction, Double accuracy);

    /** 同一 sessionId + 采集时刻 + 坐标视为重复补传，不会重复写入轨迹点。 */
    void updateLocationWithSession(Long userId, Double latitude, Double longitude, String address,
                                   Double speed, Double direction, Double accuracy, String sessionId,
                                   Long timestamp);

    /** sessionId 是幂等键：同一用户重复开始返回已有会话，被其他用户占用则拒绝。 */
    TrajectorySession startSession(Long userId, String sessionId, Long startTime);

    /** 重复结束直接返回已完成会话；durationSec 为空或负数时回退为 start→end 的时长。 */
    TrajectorySession finishSession(String sessionId, Long endTime, Integer durationSec);

    TrajectorySession getSession(String sessionId);

    /** 只返回已结束（status=1）的会话。 */
    List<TrajectorySession> listSessions(Long userId);

    List<LocationHistory> getHistoryBySession(String sessionId);

    void deleteSession(String sessionId);

    LocationVO getUserLocation(Long userId);
    List<LocationVO> getGroupLocations(Long groupId);
    List<LocationHistory> getHistory(Long userId, String date);
    List<String> getAvailableDates(Long userId, int days);
}
