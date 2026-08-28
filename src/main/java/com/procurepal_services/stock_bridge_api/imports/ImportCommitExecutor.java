package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a commit - on the caller's thread for a small file, on a worker for a large one.
 *
 * <h2>Why there are two paths at all</h2>
 * Design 11: "a progress bar on a 40-row import is worse than an instant result; a spinner on a
 * 4,000-row import is worse than a progress bar. One threshold, both behaviours, same endpoint
 * (200 vs 202)." The threshold is {@code ImportLimits.ASYNC_ROW_THRESHOLD}, shared with the
 * frontend through contract section 6.
 *
 * <p>The 202 is load-bearing and literal. Nothing else makes the client start polling: it
 * inspects {@code response.status === 202} and, on anything else, treats the body as the
 * finished result. A 200 with an empty body on the async path would leave the confirm screen
 * navigating to a result that does not exist yet.
 *
 * <h2>What a worker thread is missing</h2>
 * Neither {@code TenantContext} nor the Hibernate tenant filter travels with a thread; both are
 * set per request by the servlet filter. {@link ImportCommitWorker} restores them together - see
 * its javadoc for why doing only one of the two is the trap - and this class is only responsible
 * for getting the work to it and for making sure a failure is recorded.
 *
 * <p>The tenant id comes from the ticket, which the engine built from a row it had already loaded
 * under the requester's own tenant filter, never from a request parameter. That is the condition
 * {@code TenantScopeExecutor} places on its callers.
 *
 * <h2>Failure</h2>
 * A commit that throws rolls its transaction back, so nothing was written - but the file would
 * be left in COMMITTING forever, and the review screen redirects COMMITTING to a result screen
 * that would have nothing to show. So a failure is always recorded, in a separate transaction,
 * before the exception is allowed to matter. M5's polling contract needs the same thing from the
 * other end: the status must eventually reach COMMITTED, FAILED or EXPIRED, or the client polls
 * until its five-minute ceiling.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportCommitExecutor {

    private final ImportSessionService importSessionService;
    private final ImportCommitWorker worker;

    /**
     * A small, fixed pool. Commits are database-bound and each one holds a row lock per product
     * it touches (design 8.2 names this consequence explicitly), so more threads would mean more
     * lock contention rather than more throughput. Two is enough to stop one large import from
     * blocking another tenant's, which is the only thing concurrency is buying here.
     */
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "import-commit");
        thread.setDaemon(true);
        return thread;
    });

    /** Blocks until done, for the sync path. Exceptions are recorded and re-thrown. */
    public void runNow(ImportSessionService.CommitTicket ticket) {
        try {
            worker.run(ticket);
        } catch (RuntimeException e) {
            log.error("Import {} failed while committing", ticket.sessionId(), e);
            recordFailure(ticket);
            throw new ImportExceptions.CommitFailed(e);
        }
    }

    /** Hands off and returns immediately, for the 202 path. */
    public void runLater(ImportSessionService.CommitTicket ticket) {
        workers.submit(() -> {
            UUID previousTenant = TenantContext.get();
            try {
                // Set before the transactional call so anything reading TenantContext outside a
                // Hibernate query - StockManagementService does - sees the right tenant.
                TenantContext.set(ticket.clientId());
                worker.run(ticket);
            } catch (RuntimeException e) {
                log.error("Import {} failed while committing in the background", ticket.sessionId(), e);
                recordFailure(ticket);
            } finally {
                if (previousTenant == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previousTenant);
                }
            }
        });
    }

    private void recordFailure(ImportSessionService.CommitTicket ticket) {
        try {
            UUID previousTenant = TenantContext.get();
            TenantContext.set(ticket.clientId());
            try {
                importSessionService.markFailed(ticket);
            } finally {
                if (previousTenant == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previousTenant);
                }
            }
        } catch (RuntimeException recordingFailure) {
            // Swallowed and logged. The original failure is what the caller needs to hear about,
            // and losing it to a secondary exception raised while writing a status would make the
            // real problem invisible.
            log.error("Could not record the failure of import {}", ticket.sessionId(), recordingFailure);
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
        try {
            // A commit in flight is a transaction with real writes in it; giving it thirty
            // seconds to finish on shutdown is far better than interrupting it and relying on
            // the rollback.
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
