package hnist.communication.controller;

import hnist.communication.common.Result;
import hnist.communication.manager.mesh.MeshProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 手机无法直连 Mesh 节点时，由后端代理转发内网 cgi-bin 请求。 */
@RestController
@RequestMapping("/api/mesh")
@RequiredArgsConstructor
public class MeshProxyController {

    private final MeshProxyManager meshProxyManager;

    @GetMapping("/proxy")
    public Result<Object> proxy(@RequestParam String ip,
                                @RequestParam String path,
                                @RequestParam(defaultValue = "http") String scheme,
                                @RequestParam(required = false) Integer port,
                                HttpServletRequest request) {
        return Result.success(meshProxyManager.proxy(ip, path, scheme, port, request.getParameterMap()));
    }
}
