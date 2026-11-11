package com.procurepal_services.stock_bridge_api.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wrong passwords, counted (owner, 2026-11-01): since staff can sign in with their email or phone - easier to
 * guess than a made-up username - too many wrong passwords for one login at one company in five minutes stop it
 * until the oldest of them is five minutes old (owner: fifteen was too long to wait), and a looser limit stops one network address trying many logins. Only failures count,
 * and a correct password clears that login's count. In-process, like {@code PasswordResetRateLimiter}: a backstop
 * at today's single instance, not the security of sign-in (that is the password).
 *
 * <p>Applied whether or not the login belongs to anybody, so a refusal reveals nothing about who has an account.
 */
@Component
public class LoginRateLimiter {

    private static final int SWEEP_THRESHOLD = 10_000;

    private final int perLogin;
    private final int perAddress;
    private final Duration window;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public LoginRateLimiter(
            @Value("${app.login.failures-per-login:10}") int perLogin,
            @Value("${app.login.failures-per-address:50}") int perAddress,
            @Value("${app.login.window:PT5M}") Duration window) {
        this.perLogin = perLogin;
        this.perAddress = perAddress;
        this.window = window;
    }

    /** How long until this login may be tried again from here; zero when it may now. */
    public synchronized Duration waitFor(String company, String login, String address) {
        Instant cutoff = Instant.now().minus(window);
        Duration wait = waitFor(loginKey(company, login), perLogin, cutoff);
        Duration addressWait = waitFor(addressKey(address), perAddress, cutoff);
        return addressWait.compareTo(wait) > 0 ? addressWait : wait;
    }

    public synchronized void failed(String company, String login, String address) {
        Instant now = Instant.now();
        if (failures.size() > SWEEP_THRESHOLD) {
            Instant cutoff = now.minus(window);
            failures.entrySet().removeIf(entry -> entry.getValue().isEmpty() || !entry.getValue().peekLast().isAfter(cutoff));
        }
        failures.computeIfAbsent(loginKey(company, login), key -> new ArrayDeque<>()).addLast(now);
        failures.computeIfAbsent(addressKey(address), key -> new ArrayDeque<>()).addLast(now);
    }

    /** Signed in: that login's wrong tries are forgotten (the address's stay, they may be someone else's). */
    public synchronized void succeeded(String company, String login) {
        failures.remove(loginKey(company, login));
    }

    private Duration waitFor(String key, int limit, Instant cutoff) {
        Deque<Instant> history = failures.get(key);
        if (history == null) {
            return Duration.ZERO;
        }
        while (!history.isEmpty() && !history.peekFirst().isAfter(cutoff)) {
            history.pollFirst();
        }
        if (history.size() < limit) {
            return Duration.ZERO;
        }
        Duration wait = Duration.between(cutoff, history.peekFirst());
        return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
    }

    private static String loginKey(String company, String login) {
        return "login:" + (company == null ? "" : company.trim().toLowerCase(Locale.ROOT)) + "|"
                + (login == null ? "" : login.trim().toLowerCase(Locale.ROOT));
    }

    private static String addressKey(String address) {
        return "address:" + (address == null ? "unknown" : address);
    }
}
