package com.example.ragagent.config;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Encrypts runtime-admin secrets without ever exposing them through response DTOs. */
@Component
public class ConfigSecretCipher {
    private final String masterKey;
    private final SecureRandom random = new SecureRandom();

    public ConfigSecretCipher(@Value("${RAG_CONFIG_ENCRYPTION_KEY:}") String masterKey) {
        this.masterKey = masterKey == null ? "" : masterKey;
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return "";
        if (masterKey.isBlank()) throw new IllegalStateException("RAG_CONFIG_ENCRYPTION_KEY is required to save runtime secrets");
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer result = ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted);
            return Base64.getEncoder().encodeToString(result.array());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt runtime secret", exception);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return "";
        if (masterKey.isBlank()) throw new IllegalStateException("RAG_CONFIG_ENCRYPTION_KEY is required to read runtime secrets");
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            byte[] nonce = java.util.Arrays.copyOfRange(bytes, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(bytes, 12, bytes.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to decrypt runtime secret", exception);
        }
    }

    private SecretKeySpec key() throws Exception {
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
