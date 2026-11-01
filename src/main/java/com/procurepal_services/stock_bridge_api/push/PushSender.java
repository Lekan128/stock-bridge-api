package com.procurepal_services.stock_bridge_api.push;

import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The asynchronous half of {@link PushService}: encrypts each message for its device, signs who it's from (VAPID)
 * and hands it to the device's push service. Runs after the commit, on the push executor; a device the push
 * service says is gone (404, 410) is forgotten. Nothing here fails a request: the request is long finished.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PushSender {

    /** One device, as stored. */
    record Device(UUID id, String endpoint, String p256dh, String auth) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PushKeys pushKeys;
    private final PushTransport transport;
    private final NamedParameterJdbcTemplate jdbc;

    @Value("${app.push.subject:mailto:support@procurepaddy.com}")
    private String subject;

    @Async("pushExecutor")
    void sendAsync(List<Device> devices, PushMessage message) {
        send(devices, message);
    }

    void send(List<Device> devices, PushMessage message) {
        PushKeys.Vapid vapid = pushKeys.vapid();
        byte[] payload = JSON.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
        Map<String, String> tokens = new LinkedHashMap<>();
        for (Device device : devices) {
            try {
                String audience = audience(device.endpoint());
                String token = tokens.computeIfAbsent(audience, key -> token(key, vapid));
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "vapid t=" + token + ", k=" + vapid.publicKey());
                headers.put("Content-Encoding", "aes128gcm");
                headers.put("Content-Type", "application/octet-stream");
                // A day for something to do; an hour for "it's done" - after that the app says so anyway.
                headers.put("TTL", message.quiet() ? "3600" : "86400");
                headers.put("Urgency", message.quiet() ? "normal" : "high");
                if (message.tag() != null) {
                    // A message still waiting for an offline phone is replaced, not queued behind.
                    String topic = message.tag().replaceAll("[^A-Za-z0-9]", ""); // "request-<id>": the id's 32 hex digits
                    headers.put("Topic", topic.substring(Math.max(0, topic.length() - 32)));
                }
                byte[] body = WebPushCrypto.encrypt(payload, device.p256dh(), device.auth());
                int status = transport.send(device.endpoint(), headers, body);
                if (status == 404 || status == 410) {
                    jdbc.update("DELETE FROM push_subscriptions WHERE id = :id", new MapSqlParameterSource("id", device.id()));
                    log.info("Push subscription {} is gone ({}), removed", device.id(), status);
                } else if (status >= 200 && status < 300) {
                    jdbc.update("UPDATE push_subscriptions SET last_sent_at = now() WHERE id = :id",
                            new MapSqlParameterSource("id", device.id()));
                } else {
                    log.warn("Push to subscription {} answered {}", device.id(), status);
                }
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                log.warn("Push to subscription {} could not be sent: {}", device.id(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Push to subscription {} failed", device.id(), e);
            }
        }
    }

    /** RFC 8292: the push service's origin, signed for, for 12 hours. */
    private String token(String audience, PushKeys.Vapid vapid) {
        return Jwts.builder()
                .header().add("typ", "JWT").and()
                .audience().add(audience).and()
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(12))))
                .subject(subject)
                .signWith(vapid.privateKey(), Jwts.SIG.ES256)
                .compact();
    }

    private static String audience(String endpoint) {
        URI uri = URI.create(endpoint);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("Not a push endpoint");
        }
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
    }
}
