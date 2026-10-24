package hnist.communication.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("devices")
public class Device {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String shortName;
    private String nodeAddr;
    private String nodeMac;
    private String nodeFreq;
    private String nodeBw;
    private String nodePower;
    private String distance;
    private String meshId;
    /** AES加密: 0=AES-128 1=AES-256 2=禁用 */
    private Integer aesLevel;
    private Integer dhcpEnabled;
    private String dhcpStart;
    private String dhcpEnd;
    private String dhcpMask;
    private String dhcpDns;
    private String dhcpGateway;
    private String wifiName;
    private String wifiPassword;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
