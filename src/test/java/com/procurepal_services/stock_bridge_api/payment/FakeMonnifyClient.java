package com.procurepal_services.stock_bridge_api.payment;

import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyInitCommand;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyInitResult;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyTransactionStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Monnify that lives in memory. Substituted for {@code MonnifyRestClient} in the
 * flow tests so nothing there touches a socket.
 *
 * <p>Deliberately faithful to the real thing in the two ways the code under test
 * depends on: {@code transactionReference} contains pipes (so anything that
 * mishandles them shows up), and a transaction starts life PENDING and is settled
 * separately - because that gap between "checkout opened" and "money arrived" is
 * where every interesting bug in this module lives.
 *
 * <p>State is keyed by transactionReference so tests running against a shared
 * database never see each other's transactions.
 */
class FakeMonnifyClient implements MonnifyClient {

    private final Map<String, MonnifyTransactionStatus> statuses = new ConcurrentHashMap<>();
    private final Set<String> failingReferences = ConcurrentHashMap.newKeySet();
    private final AtomicInteger initCount = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile boolean configured = true;

    void reset() {
        statuses.clear();
        failingReferences.clear();
        initCount.set(0);
        configured = true;
    }

    void setConfigured(boolean value) {
        this.configured = value;
    }

    int initCount() {
        return initCount.get();
    }

    /** Moves a transaction to a terminal provider state, the way a real payment would. */
    void settle(String transactionReference, String paymentStatus, BigDecimal amountPaid) {
        MonnifyTransactionStatus current = statuses.get(transactionReference);
        statuses.put(
                transactionReference,
                new MonnifyTransactionStatus(
                        transactionReference,
                        current == null ? null : current.paymentReference(),
                        amountPaid,
                        current == null ? amountPaid : current.totalPayable(),
                        paymentStatus,
                        "CARD",
                        OffsetDateTime.now(),
                        // Shaped like a real responseBody, including a field we do not
                        // model, so the provider_payload assertions mean something.
                        """
                        {"transactionReference":"%s","paymentStatus":"%s","amountPaid":"%s",\
                        "settlementAmount":"%s","paymentMethod":"CARD","currency":"NGN"}"""
                                .formatted(
                                        transactionReference,
                                        paymentStatus,
                                        amountPaid,
                                        amountPaid)));
    }

    /** Makes verification of one reference blow up, simulating a provider outage. */
    void failVerifyFor(String transactionReference) {
        failingReferences.add(transactionReference);
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public MonnifyInitResult initializeTransaction(MonnifyInitCommand command) {
        if (!configured) {
            throw new MonnifyNotConfiguredException();
        }
        initCount.incrementAndGet();

        // Pipes on purpose - this is what a real reference looks like, and it is what
        // makes URL-encoding bugs observable.
        String transactionReference = "MNFY|%d|%06d"
                .formatted(System.currentTimeMillis(), sequence.incrementAndGet());

        statuses.put(
                transactionReference,
                new MonnifyTransactionStatus(
                        transactionReference,
                        command.paymentReference(),
                        null,
                        command.amount(),
                        MonnifyTransactionStatus.PENDING,
                        null,
                        null,
                        "{\"paymentStatus\":\"PENDING\"}"));

        return new MonnifyInitResult(
                // Unique per attempt: the retry test asserts a fresh URL rather than the
                // stale one, which is the whole 40-minute-expiry hazard.
                "https://sandbox.sdk.monnify.com/checkout/" + transactionReference,
                transactionReference,
                command.paymentReference());
    }

    @Override
    public MonnifyTransactionStatus getTransactionStatus(String transactionReference) {
        if (!configured) {
            throw new MonnifyNotConfiguredException();
        }
        if (failingReferences.contains(transactionReference)) {
            throw new MonnifyApiException("Simulated provider outage for " + transactionReference);
        }
        MonnifyTransactionStatus status = statuses.get(transactionReference);
        if (status == null) {
            // The real client throws for an unknown reference too, rather than
            // returning something that could be mistaken for "not paid".
            throw new MonnifyApiException("Unknown transaction " + transactionReference);
        }
        return status;
    }
}
