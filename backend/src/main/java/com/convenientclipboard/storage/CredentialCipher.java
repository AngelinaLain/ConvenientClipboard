package com.convenientclipboard.storage;

import com.convenientclipboard.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class CredentialCipher {
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();
    public CredentialCipher(AppProperties properties) {
        try {
            if(properties.storageEncryptionKey()==null || properties.storageEncryptionKey().length()<32)
                throw new IllegalArgumentException("APP_STORAGE_ENCRYPTION_KEY must contain at least 32 characters");
            key=new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(properties.storageEncryptionKey().getBytes(StandardCharsets.UTF_8)),"AES");
        } catch(java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    public String encrypt(String plaintext) {
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            byte[] encrypted=cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array());
        } catch(java.security.GeneralSecurityException ex) { throw new IllegalStateException("Credential encryption failed",ex); }
    }
    public String decrypt(String encrypted) {
        try {
            byte[] bytes=Base64.getDecoder().decode(encrypted);
            if(bytes.length<29) throw new IllegalArgumentException("Invalid encrypted credential");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,bytes,0,12));
            return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
        } catch(java.security.GeneralSecurityException|IllegalArgumentException ex) { throw new IllegalStateException("Storage credential cannot be decrypted; verify APP_STORAGE_ENCRYPTION_KEY",ex); }
    }
}
