package com.procurepal_services.stock_bridge_api.push;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Notifications on phones and computers, even with the app closed (Web Push; owner, 2026-11-01: "you get a
 * notification … so you can check and if it has been moved it goes away"). A person turns them on per device; a
 * message leaves after the caller's transaction commits and on another thread, as email does
 * ({@link com.procurepal_services.stock_bridge_api.email.EmailDispatcher}) - nothing is said about a request that
 * was rolled back, and a slow push service holds nobody up.
 */
@Service
@RequiredArgsConstructor
public class PushService {

    private final NamedParameterJdbcTemplate jdbc;
    private final PushKeys pushKeys;
    private final PushSender sender;

    /** The key browsers subscribe with (applicationServerKey). */
    public String publicKey() {
        return pushKeys.vapid().publicKey();
    }

    /** This device, for this person: a device that moves to another account (a shared phone) goes with it. */
    @Transactional
    public void subscribe(UUID clientId, UUID userId, String endpoint, String p256dh, String auth, String userAgent) {
        jdbc.update("""
                INSERT INTO push_subscriptions (id, client_id, user_id, endpoint, p256dh, auth, user_agent)
                VALUES (:id, :clientId, :userId, :endpoint, :p256dh, :auth, :userAgent)
                ON CONFLICT (endpoint) DO UPDATE SET client_id = EXCLUDED.client_id, user_id = EXCLUDED.user_id,
                    p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, user_agent = EXCLUDED.user_agent
                """, new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("clientId", clientId)
                        .addValue("userId", userId)
                        .addValue("endpoint", endpoint)
                        .addValue("p256dh", p256dh)
                        .addValue("auth", auth)
                        .addValue("userAgent", userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length()))));
    }

    /** Turned off on this device (or signed out of it): only the person's own. */
    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        jdbc.update("DELETE FROM push_subscriptions WHERE user_id = :userId AND endpoint = :endpoint",
                new MapSqlParameterSource().addValue("userId", userId).addValue("endpoint", endpoint));
    }

    /** Sends {@code message} to every device of these people, once the current transaction commits. */
    public void notify(Collection<UUID> userIds, PushMessage message) {
        if (userIds.isEmpty()) {
            return;
        }
        List<PushSender.Device> devices = jdbc.query("""
                SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ANY(:users)
                """, new MapSqlParameterSource("users", userIds.toArray(UUID[]::new)),
                (rs, row) -> new PushSender.Device(rs.getObject("id", UUID.class), rs.getString("endpoint"),
                        rs.getString("p256dh"), rs.getString("auth")));
        if (devices.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sender.sendAsync(devices, message);
                }
            });
        } else {
            sender.sendAsync(devices, message);
        }
    }
}
