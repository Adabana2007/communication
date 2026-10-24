package hnist.communication.manager.mesh;

import hnist.communication.exception.BusinessException;
import hnist.communication.manager.mesh.impl.MeshProxyManagerImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MeshProxyManagerImplTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final MeshProxyManager manager = new MeshProxyManagerImpl(restTemplate);

    @Test
    void rejectsPublicIpAndUnsupportedPathsBeforeCallingMesh() {
        BusinessException publicIp = assertThrows(BusinessException.class,
                () -> manager.proxy("8.8.8.8", "cgi_read_topo", "http", null, Map.of()));
        assertEquals("非法的内网地址", publicIp.getMessage());

        BusinessException path = assertThrows(BusinessException.class,
                () -> manager.proxy("192.168.1.8", "cgi_delete_all", "http", null, Map.of()));
        assertEquals("不支持的 mesh 接口", path.getMessage());

        verifyNoInteractions(restTemplate);
    }

    @Test
    void forwardsOnlyBusinessQueryParametersWithProtocolAndPortUnchanged() {
        Map<String, String[]> parameters = new LinkedHashMap<>();
        parameters.put("ip", new String[]{"192.168.1.8"});
        parameters.put("path", new String[]{"cgi_read_topo"});
        parameters.put("scheme", new String[]{"http"});
        parameters.put("port", new String[]{"8080"});
        parameters.put("name", new String[]{"front room"});
        when(restTemplate.getForObject(any(URI.class), eq(Object.class))).thenReturn(Map.of("ok", true));

        Object result = manager.proxy("192.168.1.8", "cgi_read_topo", "HTTP", 8080, parameters);

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForObject(uri.capture(), eq(Object.class));
        assertEquals("http://192.168.1.8:8080/cgi-bin/cgi_read_topo?name=front%20room", uri.getValue().toString());
        assertEquals(Map.of("ok", true), result);
    }

    @Test
    void convertsConnectionTimeoutIntoCompatibleBusinessMessage() {
        when(restTemplate.getForObject(any(URI.class), eq(Object.class)))
                .thenThrow(new ResourceAccessException("I/O error: connection timed out"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> manager.proxy("10.0.0.8", "cgi_read_nodeinfo", "http", 80, Map.of()));

        assertTrue(error.getMessage().contains("10.0.0.8:80"));
        assertTrue(error.getMessage().contains("连接超时"));
    }
}
