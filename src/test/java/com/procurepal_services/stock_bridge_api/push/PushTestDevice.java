package com.procurepal_services.stock_bridge_api.push;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** A browser, as far as push goes: its keys, the subscription it would hand over, and reading what arrives. */
public final class PushTestDevice {

    private final KeyPair keys;
    private final byte[] auth = new byte[16];
    private final String endpoint;

    public PushTestDevice() {
        try {
            keys = WebPushCrypto.newKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        new SecureRandom().nextBytes(auth);
        endpoint = "https://push.example.test/send/" + UUID.randomUUID();
    }

    public String endpoint() {
        return endpoint;
    }

    /** The body of POST /api/push/subscriptions. */
    public Map<String, Object> subscription() {
        return Map.of("endpoint", endpoint, "keys", Map.of("p256dh", p256dh(), "auth", WebPushCrypto.base64Url(auth)));
    }

    String p256dh() {
        return WebPushCrypto.base64Url(WebPushCrypto.uncompressed((ECPublicKey) keys.getPublic()));
    }

    String auth() {
        return WebPushCrypto.base64Url(auth);
    }

    /** What the service worker would read: RFC 8291, the other way. */
    public String read(byte[] body) throws GeneralSecurityException {
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int idLength = body[20] & 0xff;
        byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idLength);
        byte[] ciphertext = Arrays.copyOfRange(body, 21 + idLength, body.length);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(keys.getPrivate());
        agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
        byte[] uaPublic = WebPushCrypto.uncompressed((ECPublicKey) keys.getPublic());
        byte[][] keyAndNonce = WebPushCrypto.deriveKeyAndNonce(agreement.generateSecret(), auth, uaPublic, asPublic, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyAndNonce[0], "AES"), new GCMParameterSpec(128, keyAndNonce[1]));
        byte[] plain = cipher.doFinal(ciphertext);
        int end = plain.length - 1;
        while (end >= 0 && plain[end] == 0) {
            end--;
        }
        if (end < 0 || plain[end] != 0x02) {
            throw new GeneralSecurityException("No last-record delimiter");
        }
        return new String(plain, 0, end, StandardCharsets.UTF_8);
    }
}
