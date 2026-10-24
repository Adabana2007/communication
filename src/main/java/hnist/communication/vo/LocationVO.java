package hnist.communication.vo;

import hnist.communication.entity.UserLocation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationVO {
    private Long id;
    private Long userId;
    /** 用户名（非DB字段，查询时填充） */
    private String username;
    private Double latitude;
    private Double longitude;
    private String address;
    /** 上报时间 */
    private LocalDateTime updatedAt;

    public static LocationVO from(UserLocation loc, String username) {
        return LocationVO.builder()
                .id(loc.getId()).userId(loc.getUserId()).username(username)
                .latitude(loc.getLatitude()).longitude(loc.getLongitude())
                .address(loc.getAddress()).updatedAt(loc.getUpdatedAt()).build();
    }
}
