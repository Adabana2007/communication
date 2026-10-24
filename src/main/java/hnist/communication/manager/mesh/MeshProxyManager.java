package hnist.communication.manager.mesh;

import java.util.Map;

/** Mesh 节点 CGI 代理能力，负责目标校验、参数透传与第三方响应转换。 */
public interface MeshProxyManager {

    /** 代理访问指定内网 Mesh 节点，返回第三方接口的数据体。 */
    Object proxy(String ip, String path, String scheme, Integer port, Map<String, String[]> parameters);
}
