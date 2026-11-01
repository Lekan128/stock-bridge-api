package com.procurepal_services.stock_bridge_api.push;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The server's VAPID key pair (RFC 8292): made on first use and kept in {@code push_vapid}, so every device that
 * subscribed keeps hearing from us across restarts and deploys, with nothing to configure.
 */
@Component
@RequiredArgsConstructor
class PushKeys {

    /** The public key as browsers take it (base64url, uncompressed P-256), and the private one to sign with. */
    record Vapid(String publicKey, PrivateKey privateKey) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private volatile Vapid cached;

    @Transactional
    Vapid vapid() {
        Vapid known = cached;
        if (known != null) {
            return known;
        }
        try {
            List<Map<String, Object>> row = jdbc.queryForList("SELECT public_key, private_key FROM push_vapid WHERE id = 1",
                    new MapSqlParameterSource());
            if (row.isEmpty()) {
                KeyPair pair = WebPushCrypto.newKeyPair();
                jdbc.update("""
                        INSERT INTO push_vapid (id, public_key, private_key) VALUES (1, :publicKey, :privateKey)
                        ON CONFLICT (id) DO NOTHING
                        """, new MapSqlParameterSource()
                                .addValue("publicKey", WebPushCrypto.base64Url(WebPushCrypto.uncompressed((ECPublicKey) pair.getPublic())))
                                .addValue("privateKey", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())));
                // Read back: two first uses at once keep the same pair.
                row = jdbc.queryForList("SELECT public_key, private_key FROM push_vapid WHERE id = 1", new MapSqlParameterSource());
            }
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode((String) row.getFirst().get("private_key"))));
            Vapid vapid = new Vapid((String) row.getFirst().get("public_key"), privateKey);
            cached = vapid;
            return vapid;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The push key pair could not be read", e);
        }
    }
}
