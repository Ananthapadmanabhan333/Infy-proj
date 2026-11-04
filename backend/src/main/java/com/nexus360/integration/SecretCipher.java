package com.nexus360.integration;

import com.nexus360.config.AppProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for integration secrets at rest. Uses INTEGRATION_ENCRYPTION_KEY (base64, 32 bytes) when set,
 * otherwise a key derived from the JWT secret so secrets are never stored in plain text.
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public SecretCipher(AppProperties properties) {
        String configured = properties.integrations().encryptionKey();
        byte[] raw;
        if (configured != null && !configured.isBlank()) {
            raw = Base64.getDecoder().decode(configured.trim());
            if (raw.length != 32) {
                throw new IllegalStateException("INTEGRATION_ENCRYPTION_KEY must be 32 bytes, base64 encoded");
            }
        } else {
            raw = sha256(("nexus360-integrations:" + properties.security().jwtSecret()).getBytes(StandardCharsets.UTF_8));
            log.info("INTEGRATION_ENCRYPTION_KEY not set; deriving the integration secret key from JWT_SECRET");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt secret", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.error("Could not decrypt an integration secret (was the encryption key changed?)");
            return null;
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
