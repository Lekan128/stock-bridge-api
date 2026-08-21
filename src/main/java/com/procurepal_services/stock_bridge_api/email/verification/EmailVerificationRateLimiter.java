package com.procurepal_services.stock_bridge_api.email.verification;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Caps how often one account may ask ProcurePal to email a verification link.
 *
 * <h2>Why this exists at all</h2>
 * "Resend my link" is a primitive that causes this server to send mail to an
 * address chosen by the caller - a signed-in user can point their profile email at
 * anyone and press the button. Unlimited, that is a mail cannon with ProcurePal's
 * verified sending identity on the envelope and somebody else's inbox as the
 * target, and the damage does not land on the abuser: complaints and bounces are
 * scored against the sending DOMAIN, which every tenant shares. One bored account
 * could get order receipts suspended for the entire platform. That is precisely
 * the outcome {@code EmailEligibility} was written to avoid, arriving through the
 * one endpoint that is allowed to bypass it - see {@code EmailKind.VERIFICATION},
 * which by design cannot be gated.
 *
 * <h2>Why in-process, and what that honestly does not cover</h2>
 * A {@code ConcurrentHashMap} of recent timestamps, and nothing else. There is no
 * rate-limiting mechanism anywhere in this codebase to reuse, and the alternatives
 * both cost more than this is worth: Redis is an entire new dependency and a new
 * failure mode on a path that must never fail, and a database-backed count
 * (already available - see
 * {@code EmailVerificationTokenRepository.countByUserIdAndCreatedAtAfter}) puts a
 * write-path query behind every press of a button most users press zero times.
 *
 * <p>The honest limitations, so nobody discovers them as a surprise:
 * <ul>
 *   <li><strong>Per instance.</strong> Two application instances behind a load
 *       balancer each allow the full budget, so the real platform-wide limit is
 *       the configured one times the instance count. For a limit whose job is to
 *       turn "unbounded" into "a handful", a factor of two or three does not
 *       change the answer.</li>
 *   <li><strong>Forgotten on restart.</strong> A deploy resets every counter. Same
 *       reasoning: an attacker who can force a redeploy has better options.</li>
 *   <li><strong>Keyed by user, not by target address.</strong> An attacker with
 *       N accounts gets N budgets. That is not a new primitive though - signup
 *       already mails one message to any address a stranger types, without
 *       authenticating anything - so a per-address limit here would close a door
 *       in a wall that has a larger opening beside it. If abuse ever appears, the
 *       fix belongs at signup and applies to both.</li>
 * </ul>
 *
 * <p>Upgrade path if any of that stops being acceptable: swap the map for the
 * repository count above. The {@link Verdict} contract does not change.
 *
 * <h2>Rolling window, not calendar buckets</h2>
 * Timestamps are kept and pruned rather than counted into a fixed hour. A fixed
 * hourly bucket lets a caller spend the whole budget at 10:59 and the whole next
 * one at 11:01 - double the intended rate at the moment it matters most, which is
 * a burst. Pruning entries older than the window makes the limit mean what it
 * says at every instant.
 *
 * <h2>Never throws, and never blocks a send it has already allowed</h2>
 * This class decides; the caller acts. It returns a {@link Verdict} rather than
 * throwing, so the service can turn a refusal into an ordinary HTTP response
 * instead of an exception crossing a transaction boundary - the hazard
 * {@code EmailRecipients} documents at length. Note the ordering consequence: a
 * consumed slot is spent whether or not the email that follows actually leaves
 * SES. That is the safe direction. Counting only successful sends would let a
 * misconfigured deploy, where every send fails, be hammered without limit.
 */
@Component
@RequiredArgsConstructor
public class EmailVerificationRateLimiter {

    /**
     * How many tracked accounts it takes before a call bothers sweeping the whole
     * map for expired entries. Entries are pruned on access anyway, so this only
     * reclaims users who asked once and never came back; without it the map is a
     * slow leak over a long-lived process, and with it the sweep is amortised to
     * nearly never. The number is arbitrary and only has to be large enough that
     * normal traffic never triggers it.
     */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final EmailVerificationProperties properties;

    /**
     * user id to the instants at which that user was last allowed a send, oldest
     * first. Each deque is guarded by synchronizing on itself rather than by being
     * a concurrent collection: the check-prune-append sequence has to be atomic as
     * a unit, and {@code ConcurrentLinkedDeque} would make each step atomic and the
     * sequence not - which is how two simultaneous requests both see "one slot
     * left". The critical section is a handful of comparisons on a list of at most
     * {@code resendLimit} entries.
     */
    private final Map<UUID, Deque<Instant>> recentSends = new ConcurrentHashMap<>();

    /**
     * Consumes a slot if one is free.
     *
     * @param userId the account asking; never null on any path that reaches here,
     *     since the endpoint behind it is authenticated
     * @return allowed, or refused with how long until the oldest recorded send
     *     falls out of the window
     */
    public Verdict tryAcquire(UUID userId) {
        Instant now = Instant.now();
        Duration window = properties.resendWindow();
        Instant cutoff = now.minus(window);

        if (recentSends.size() > SWEEP_THRESHOLD) {
            sweep(cutoff);
        }

        Deque<Instant> history = recentSends.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (history) {
            while (!history.isEmpty() && !history.peekFirst().isAfter(cutoff)) {
                history.pollFirst();
            }
            if (history.size() >= properties.resendLimit()) {
                // The oldest entry is what has to age out before a slot frees up.
                Duration retryAfter = Duration.between(cutoff, history.peekFirst());
                return Verdict.refused(retryAfter.isNegative() ? Duration.ZERO : retryAfter);
            }
            history.addLast(now);
            return Verdict.allow();
        }
    }

    /**
     * Drops accounts whose whole history has aged out. Deliberately tolerant of
     * racing with {@link #tryAcquire}: the worst outcome of removing a deque that
     * another thread is about to append to is that one caller's single recorded
     * send is forgotten, which costs at most one extra permitted email.
     */
    private void sweep(Instant cutoff) {
        recentSends.entrySet().removeIf(entry -> {
            Deque<Instant> history = entry.getValue();
            synchronized (history) {
                return history.isEmpty() || !history.peekLast().isAfter(cutoff);
            }
        });
    }

    /**
     * The decision, plus the one thing a refused caller can usefully be told.
     *
     * <p>{@code retryAfter} exists so the API can answer with something actionable
     * rather than a bare 429 - the frontend disables the button for that long
     * instead of letting a user click it six more times and learn nothing.
     */
    public record Verdict(boolean allowed, Duration retryAfter) {

        /**
         * Named {@code allow}, not {@code allowed}, and that is forced rather than
         * chosen: a record's accessor for the {@code allowed} component is itself
         * called {@code allowed()}, so a static factory of that name is a compile
         * error ("invalid accessor method"). The component keeps the readable name
         * because it is the one callers say out loud.
         */
        static Verdict allow() {
            return new Verdict(true, Duration.ZERO);
        }

        static Verdict refused(Duration retryAfter) {
            return new Verdict(false, retryAfter);
        }
    }
}
