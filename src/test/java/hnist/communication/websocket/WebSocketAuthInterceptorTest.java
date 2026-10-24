package hnist.communication.websocket;

import hnist.communication.security.JwtUtils;
import hnist.communication.entity.User;
import hnist.communication.mapper.UserMapper;
import hnist.communication.registry.LoginSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketAuthInterceptorTest {
    private final JwtUtils jwt = new JwtUtils("isolated-websocket-auth-test-key-not-for-deployment-20261012", 3600000L);
    private final LoginSessionRegistry registry = new LoginSessionRegistry();
    private UserMapper users;
    private User user;
    private WebSocketAuthInterceptor interceptor;
    private ServerHttpResponse response;
    private Map<String, Object> attributes;

    @BeforeEach void setup() {
        users = mock(UserMapper.class);
        user = User.builder().userId(7L).username("pc_test").groupId(1L).build();
        when(users.selectById(7L)).thenReturn(user);
        interceptor = new WebSocketAuthInterceptor(jwt, registry, users);
        response = mock(ServerHttpResponse.class);
        attributes = new HashMap<>();
    }

    private String token() { return jwt.generateToken(7L, "pc_test", 0, registry.currentGeneration(7L)); }
    private boolean connect(String query) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/ws/location" + query));
        return interceptor.beforeHandshake(request, response, null, attributes);
    }

    @Test void matchingGroupKeepsExistingAppRoomContract() {
        assertTrue(connect("?groupId=1&token=" + token()));
        assertEquals(7L, attributes.get("userId"));
        assertEquals(1L, attributes.get("groupId"));
        assertEquals(false, attributes.get("presenceOnly"));
        verifyNoInteractions(response);
    }

    @Test void mismatchedGroupOnlyRegistersPresenceNeverRequestedRoom() {
        assertTrue(connect("?groupId=999&token=" + token()));
        assertEquals(true, attributes.get("presenceOnly"));
        assertEquals(1L, attributes.get("groupId"));
        assertNotEquals(999L, attributes.get("groupId"));
    }

    @Test void missingGroupStillRegistersAuthenticatedPcPresence() {
        assertTrue(connect("?token=" + token()));
        assertEquals(true, attributes.get("presenceOnly"));
        assertEquals(1L, attributes.get("groupId"));
    }

    @Test void invalidLegacyGroupOnlyAllowsPresence() {
        assertTrue(connect("?groupId=undefined&token=" + token()));
        assertEquals(true, attributes.get("presenceOnly"));
        assertEquals(1L, attributes.get("groupId"));
    }

    @Test void userWithoutDefaultGroupDoesNotReceiveInventedRoom() {
        user.setGroupId(null);
        assertTrue(connect("?groupId=1&token=" + token()));
        assertEquals(true, attributes.get("presenceOnly"));
        assertFalse(attributes.containsKey("groupId"));
    }

    @Test void suppliedUserIdCannotSpoofAuthenticatedIdentity() {
        assertTrue(connect("?groupId=1&userId=999&token=" + token()));
        assertEquals(7L, attributes.get("userId"));
    }

    @Test void missingTokenIsStillRejected() {
        assertFalse(connect("?groupId=1"));
        assertTrue(attributes.isEmpty());
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test void invalidTokenIsStillRejected() {
        assertFalse(connect("?groupId=1&token=not-a-token"));
        assertTrue(attributes.isEmpty());
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test void supersededTokenCannotUsePresenceCompatibility() {
        String old = token();
        registry.startNewSession(7L);
        assertFalse(connect("?groupId=999&token=" + old));
        assertTrue(attributes.isEmpty());
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test void deletedUserCannotRegisterPresence() {
        when(users.selectById(7L)).thenReturn(null);
        assertFalse(connect("?token=" + token()));
        assertTrue(attributes.isEmpty());
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}
