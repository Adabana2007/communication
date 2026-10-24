package hnist.communication.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MapProvinceVO {
    private Long id;
    private String name;
    private Integer downloadedCount;
    private Integer totalCount;
}
