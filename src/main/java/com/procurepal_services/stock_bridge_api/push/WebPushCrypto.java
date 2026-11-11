package com.procurepal_services.stock_bridge_api.push;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The cryptography of a Web Push message, with the JDK alone: P-256 keys in the uncompressed form browsers use, and
 * the message encryption of RFC 8291 ("aes128gcm", RFC 8188) - an ephemeral key per message, agreed with the
 * device's key and its auth secret, so only that device can read it.
 */
final class WebPushCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    /** One record holds the whole message (they are small); the size the header declares. */
    private static final int RECORD_SIZE = 4096;

    private WebPushCrypto() {
    }

    static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    /** The point as browsers take it: 0x04, then X and Y, 32 bytes each. */
    static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        copyFixed(key.getW().getAffineX(), out, 1);
        copyFixed(key.getW().getAffineY(), out, 33);
        return out;
    }

    static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new GeneralSecurityException("Not an uncompressed P-256 point");
        }
        ECParameterSpec params = ((ECPublicKey) newKeyPair().getPublic()).getParams();
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, params));
    }

    static String base64Url(byte[] bytes) {
        return URL.encodeToString(bytes);
    }

    static byte[] fromBase64Url(String text) {
        return URL_DECODER.decode(text.trim());
    }

    /**
     * {@code payload} encrypted for the device whose key is {@code p256dh} and auth secret {@code auth} (both base64url,
     * as the browser hands them over): the whole body of the push request.
     */
    static byte[] encrypt(byte[] payload, String p256dh, String auth) throws GeneralSecurityException {
        byte[] uaPublic = fromBase64Url(p256dh);
        byte[] authSecret = fromBase64Url(auth);
        KeyPair ephemeral = newKeyPair();
        byte[] asPublic = uncompressed((ECPublicKey) ephemeral.getPublic());

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate());
        agreement.doPhase(publicKey(uaPublic), true);
        byte[] ecdhSecret = agreement.generateSecret();

        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[][] keyAndNonce = deriveKeyAndNonce(ecdhSecret, authSecret, uaPublic, asPublic, salt);

        byte[] plaintext = Arrays.copyOf(payload, payload.length + 1);
        plaintext[payload.length] = 0x02; // the last (and only) record
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyAndNonce[0], "AES"), new GCMParameterSpec(128, keyAndNonce[1]));
        byte[] ciphertext = cipher.doFinal(plaintext);

        ByteBuffer body = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ciphertext.length);
        body.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(ciphertext);
        return body.array();
    }

    /**
     * RFC 8291 section 3.4: the input key from the shared secret and the device's auth secret, then (RFC 8188) the
     * content key and nonce from it and the salt.
     */
    static byte[][] deriveKeyAndNonce(byte[] ecdhSecret, byte[] authSecret, byte[] uaPublic, byte[] asPublic, byte[] salt)
            throws GeneralSecurityException {
        ByteArrayOutputStream keyInfo = new ByteArrayOutputStream();
        keyInfo.writeBytes("WebPush: info".getBytes(StandardCharsets.US_ASCII));
        keyInfo.write(0);
        keyInfo.writeBytes(uaPublic);
        keyInfo.writeBytes(asPublic);
        byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo.toByteArray(), 32);
        byte[] cek = hkdf(salt, ikm, info("Content-Encoding: aes128gcm"), 16);
        byte[] nonce = hkdf(salt, ikm, info("Content-Encoding: nonce"), 12);
        return new byte[][] {cek, nonce};
    }

    private static byte[] info(String label) {
        byte[] text = label.getBytes(StandardCharsets.US_ASCII);
        return Arrays.copyOf(text, text.length + 1);
    }

    /** HKDF (RFC 5869) with SHA-256, for at most one block of output - all this protocol needs. */
    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = extract.doFinal(ikm);
        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        expand.update(info);
        expand.update((byte) 0x01);
        return Arrays.copyOf(expand.doFinal(), length);
    }

    private static void copyFixed(BigInteger value, byte[] out, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, out, offset + 32 - length, length);
    }
}
