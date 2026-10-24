package hnist.communication.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SensitiveDataMaskerTest {

    @Test
    void masksPhoneNumbersWithoutLeakingMiddleDigits() {
        assertEquals("138****5678", SensitiveDataMasker.maskPhone("13812345678"));
        assertEquals("1*3", SensitiveDataMasker.maskPhone("123"));
        assertEquals("*", SensitiveDataMasker.maskPhone("1"));
        assertNull(SensitiveDataMasker.maskPhone(null));
        assertEquals(" ", SensitiveDataMasker.maskPhone(" "));
    }

    @Test
    void masksEmailLocalPartAndKeepsDomain() {
        assertEquals("a***@example.com", SensitiveDataMasker.maskEmail("alice@example.com"));
        assertEquals("b***", SensitiveDataMasker.maskEmail("bad"));
        assertNull(SensitiveDataMasker.maskEmail(null));
    }

    @Test
    void masksConfiguredSecretsButKeepsMissingValuesDistinguishable() {
        assertEquals("******", SensitiveDataMasker.maskSecret("wifi-password"));
        assertEquals("", SensitiveDataMasker.maskSecret(""));
        assertNull(SensitiveDataMasker.maskSecret(null));
    }

    @Test
    void masksCredentialsEmbeddedInUrlsButKeepsHostPathAndNonSecretParameters() {
        assertEquals("rtsp://******@192.0.2.7/live",
                SensitiveDataMasker.maskUrlCredentials("rtsp://operator:secret@192.0.2.7/live"));
        assertEquals("rtsp://192.0.2.7/live?token=******&channel=1",
                SensitiveDataMasker.maskUrlCredentials(
                        "rtsp://192.0.2.7/live?token=abc&channel=1"));
        assertEquals("http://192.0.2.7/snapshot?password=******&width=640",
                SensitiveDataMasker.maskUrlCredentials(
                        "http://192.0.2.7/snapshot?password=p@ss&width=640"));
        assertNull(SensitiveDataMasker.maskUrlCredentials(null));
        assertEquals(" ", SensitiveDataMasker.maskUrlCredentials(" "));
    }
}
