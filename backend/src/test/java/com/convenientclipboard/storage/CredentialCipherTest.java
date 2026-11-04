package com.convenientclipboard.storage;

import com.convenientclipboard.config.AppProperties;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CredentialCipherTest {
    private CredentialCipher cipher(String key) { return new CredentialCipher(new AppProperties("http://localhost",Path.of("."),1048576,"j".repeat(32),"admin","password",key)); }
    @Test void encryptedCredentialsAreRandomizedAndAuthenticated() {
        CredentialCipher cipher=cipher("k".repeat(32));
        String first=cipher.encrypt("secret-access-key"),second=cipher.encrypt("secret-access-key");
        assertNotEquals(first,second);assertFalse(first.contains("secret-access-key"));assertEquals("secret-access-key",cipher.decrypt(first));
        assertThrows(IllegalStateException.class,()->cipher("x".repeat(32)).decrypt(first));
    }
}
