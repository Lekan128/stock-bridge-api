package com.procurepal_services.stock_bridge_api.imports;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Throws away uploaded files nobody came back to - design 11's forty-eight-hour TTL.
 *
 * <p>The UI mentions it once ("we'll keep this for 2 days") and that promise has to be kept in
 * both directions: the file is still there tomorrow, and it is gone the day after. Without the
 * sweep, {@code import_session_rows} accumulates one row per spreadsheet line of every abandoned
 * upload forever, which at the 5,000-row cap is a table that grows faster than the products
 * table it exists to populate.
 *
 * <h2>Why it never touches a committed import</h2>
 * {@code collectExpired} enumerates the statuses it will collect and COMMITTED and COMMITTING are
 * not among them. Both {@code import_batch_id} foreign keys are {@code ON DELETE RESTRICT}, so
 * the delete would fail against every product and movement the import stamped - and if it
 * somehow succeeded it would erase the provenance of real inventory, which is the thing the
 * stamp exists to record. M1 designed {@code findExpiredUncommitted} to be paged and NOT
 * tenant-scoped precisely so this job could run with no {@code TenantContext}, and left
 * {@code ImportSession.isUncommitted()} stating the rule once.
 *
 * <h2>Paged, and idempotent</h2>
 * One page per run rather than the whole backlog, so a first run after a long outage is a series
 * of small transactions instead of one enormous one. Anything left over is collected on the next
 * pass - there is no deadline on garbage.
 *
 * <p>Modelled on {@code EscrowReleaseSweep}: the exception is swallowed and logged rather than
 * propagated, because an exception out of a {@code @Scheduled} method cancels nothing but this
 * run and the next one retries the same rows anyway. Left unlogged it would simply be invisible.
 * Unlike that one this defaults to ON, because it deletes only work-in-progress that has already
 * been promised an expiry, and never anything that represents money or stock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportExpirySweep {

    /** One page per run. Large enough to keep up, small enough to be one short transaction. */
    private static final int BATCH_SIZE = 100;

    private final ImportSessionService importSessionService;

    @Value("${app.imports.expiry.enabled:true}")
    private boolean enabled;

    @Scheduled(
            fixedDelayString = "${app.imports.expiry.interval:PT1H}",
            initialDelayString = "${app.imports.expiry.interval:PT1H}")
    public void scheduledSweep() {
        if (!enabled) {
            return;
        }
        try {
            int collected = importSessionService.collectExpired(BATCH_SIZE);
            if (collected > 0) {
                log.info("Discarded {} uploaded import file(s) that passed their two-day limit", collected);
            }
        } catch (RuntimeException ex) {
            log.error("Import expiry sweep failed; the next run will retry the same files", ex);
        }
    }
}
