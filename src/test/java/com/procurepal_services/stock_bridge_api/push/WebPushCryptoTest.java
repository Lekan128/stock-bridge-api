package com.procurepal_services.stock_bridge_api.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPrivateKeySpec;
import javax.crypto.KeyAgreement;
import org.junit.jupiter.api.Test;

class WebPushCryptoTest {

    @Test
    void onlyTheDeviceItWasMadeForCanReadIt() throws GeneralSecurityException {
        PushTestDevice device = new PushTestDevice();
        byte[] body = WebPushCrypto.encrypt("{\"title\":\"The Kitchen asked for 2 items\"}".getBytes(StandardCharsets.UTF_8),
                device.p256dh(), device.auth());

        assertThat(device.read(body)).isEqualTo("{\"title\":\"The Kitchen asked for 2 items\"}");
        // The aes128gcm header: a 16-byte salt, the record size, and the server's one-time key.
        assertThat(ByteBuffer.wrap(body, 16, 4).getInt()).isEqualTo(4096);
        assertThat(body[20]).isEqualTo((byte) 65);
        assertThat(body[21]).isEqualTo((byte) 0x04);
        assertThatThrownBy(() -> new PushTestDevice().read(body)).isInstanceOf(GeneralSecurityException.class);
    }

    /** The worked example in RFC 8291 section 5: the same keys and salt give the same content key, nonce and body. */
    @Test
    void matchesTheStandardsWorkedExample() throws GeneralSecurityException {
        byte[] asPublic = WebPushCrypto.fromBase64Url(
                "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        byte[] uaPublic = WebPushCrypto.fromBase64Url(
                "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] salt = WebPushCrypto.fromBase64Url("DGv6ra1nlYgDCS1FRnbzlw");
        byte[] auth = WebPushCrypto.fromBase64Url("BTBZMqHH6r4Tts7J_aSIgg");
        PrivateKey asPrivate = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(
                new BigInteger(1, WebPushCrypto.fromBase64Url("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")),
                WebPushCrypto.publicKey(asPublic).getParams()));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(asPrivate);
        agreement.doPhase(WebPushCrypto.publicKey(uaPublic), true);

        byte[][] keyAndNonce = WebPushCrypto.deriveKeyAndNonce(agreement.generateSecret(), auth, uaPublic, asPublic, salt);

        assertThat(WebPushCrypto.base64Url(keyAndNonce[0])).isEqualTo("oIhVW04MRdy2XN9CiKLxTg");
        assertThat(WebPushCrypto.base64Url(keyAndNonce[1])).isEqualTo("4h_95klXJ5E_qnoN");
    }

    @Test
    void aKeyGoesToBrowsersAndBackUnchanged() throws GeneralSecurityException {
        KeyPair pair = WebPushCrypto.newKeyPair();
        byte[] raw = WebPushCrypto.uncompressed((ECPublicKey) pair.getPublic());

        assertThat(raw).hasSize(65);
        assertThat(WebPushCrypto.publicKey(raw).getW()).isEqualTo(((ECPublicKey) pair.getPublic()).getW());
        assertThat(WebPushCrypto.fromBase64Url(WebPushCrypto.base64Url(raw))).isEqualTo(raw);
    }
}
