package hnist.communication.vo;

import hnist.communication.entity.Alarm;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 告警详情 VO（后台报警管理用：带报警人用户名/昵称）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlarmVO {
    private Long id;
    private Long userId;
    /** 报警人用户名 */
    private String username;
    /** 报警人昵称 */
    private String nickname;
    private String description;
    private Double latitude;
    private Double longitude;
    /** JSON 数组字符串（图片访问 URL，/api/alarms/media/xxx） */
    private String imagePaths;
    /** 语音访问 URL（/api/alarms/media/xxx）或手机本地路径 */
    private String voicePath;
    private Integer voiceDuration;
    /** pending/processing/done */
    private String status;
    private String handleRemark;
    private Long handledBy;
    private String handledByName;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;
    /** 媒体ID（按 /api/alarms/media/{id} 访问） */
    private java.util.List<Long> imageMediaIds;
    private Long voiceMediaId;

    public static AlarmVO from(Alarm a, String username, String nickname, String handledByName) {
        return AlarmVO.builder()
                .id(a.getId()).userId(a.getUserId())
                .username(username).nickname(nickname)
                .description(a.getDescription())
                .latitude(a.getLatitude()).longitude(a.getLongitude())
                .imagePaths(a.getImagePaths()).voicePath(a.getVoicePath())
                .voiceDuration(a.getVoiceDuration())
                .status(a.getStatus()).handleRemark(a.getHandleRemark())
                .handledBy(a.getHandledBy()).handledByName(handledByName)
                .handledAt(a.getHandledAt()).createdAt(a.getCreatedAt())
                .build();
    }
}
