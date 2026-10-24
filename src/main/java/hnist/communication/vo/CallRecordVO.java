package hnist.communication.vo;

import hnist.communication.entity.CallRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallRecordVO {
    private Long id;
    private String roomId;
    private Long callerId;
    private String callerName;
    private Long calleeId;
    private String calleeName;
    private Integer mediaType;
    private Integer callType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationSeconds;
    private String status;
    private String recordFileUrl;
    private LocalDateTime createdAt;

    public static CallRecordVO from(CallRecord record, String callerName, String calleeName) {
        return CallRecordVO.builder()
                .id(record.getId()).roomId(record.getRoomId())
                .callerId(record.getCallerId()).callerName(callerName)
                .calleeId(record.getCalleeId()).calleeName(calleeName)
                .mediaType(record.getMediaType()).callType(record.getCallType())
                .startTime(record.getStartTime()).endTime(record.getEndTime())
                .durationSeconds(record.getDurationSeconds()).status(record.getStatus())
                .recordFileUrl(record.getRecordFileUrl()).createdAt(record.getCreatedAt())
                .build();
    }
}
