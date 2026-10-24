package hnist.communication.manager.recording;

import hnist.communication.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CallMediaPresenceTest {
    @Test void peerPulseIsDistinctFromServersAvailabilityAndScopedToParticipants() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(anyString(), eq(Map.class))).thenReturn(Map.of("items", List.of(
            Map.of("name","stream_call_fixture_1","ready",true), Map.of("name","stream_call_fixture_2","ready",true), Map.of("name","stream_call_fixture_3","ready",true))));
        var presence = new CallMediaPresence(http, "http://fixture");
        var own = presence.pulse(1,"call_fixture",2,false,false); assertTrue(own.known()); assertEquals(-1, own.peerAgeMs());
        presence.pulse(2,"call_fixture",1,true,true); var peer = presence.pulse(1,"call_fixture",2,false,false);
        assertTrue(peer.peerAgeMs() >= 0); assertTrue(peer.peerCameraOff()); assertTrue(peer.peerMuted());
        assertThrows(BusinessException.class, () -> presence.pulse(3,"call_fixture",2,false,false));
        assertThrows(BusinessException.class, () -> presence.requireStreams(3,"call_fixture",2));
    }
    @Test void unavailableMediaApiIsUnknownNotConfirmationOfPeerExit() {
        RestTemplate http = mock(RestTemplate.class); when(http.getForObject(anyString(),eq(Map.class))).thenThrow(new RuntimeException("network"));
        var presence = new CallMediaPresence(http,"http://fixture");
        assertFalse(presence.pulse(1,"call_fixture",2,false,false).known());
        assertThrows(BusinessException.class, () -> presence.requireStreams(1,"call_fixture",2));
    }
    @Test void unavailableOwnStreamCannotRegisterArbitraryCall() {
        RestTemplate http = mock(RestTemplate.class); when(http.getForObject(anyString(),eq(Map.class))).thenReturn(Map.of("items",List.of()));
        var presence = new CallMediaPresence(http,"http://fixture");
        presence.pulse(1,"call_fixture",2,false,false);
        assertThrows(BusinessException.class, () -> presence.requireStreams(1,"call_fixture",2));
        assertThrows(BusinessException.class, () -> presence.pulse(1,"../secret",2,false,false));
    }
    @Test void unpublishedOutsiderCannotReadAnExistingPeersFlags() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(anyString(), eq(Map.class))).thenReturn(Map.of("items", List.of(
            Map.of("name","stream_call_fixture_1","ready",true), Map.of("name","stream_call_fixture_2","ready",true))));
        var presence = new CallMediaPresence(http,"http://fixture");
        presence.pulse(1,"call_fixture",2,false,false); presence.pulse(2,"call_fixture",1,true,true);
        assertThrows(BusinessException.class, () -> presence.pulse(3,"call_fixture",2,false,false));
    }
}
