package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.tenant.TenantScopeExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction a commit runs inside, and the tenant it runs as.
 *
 * <h2>Why this is its own bean rather than a method on {@link ImportCommitExecutor}</h2>
 * Spring's {@code @Transactional} is applied by a proxy, so a method calling another method on
 * {@code this} bypasses it entirely and runs with no transaction at all. The executor has to
 * call this from two places - once inline for a small file, once from a worker thread for a
 * large one - and both of those are self-calls if the method lives beside them. Silently running
 * an all-or-nothing batch write outside a transaction is close to the worst failure this module
 * could have, and it would show up as partial imports that nobody could reproduce. One extra
 * class removes the possibility rather than relying on nobody ever refactoring it back.
 *
 * <h2>Why the tenant is re-established here</h2>
 * On the worker thread there is no request, so neither {@code TenantContext} nor the Hibernate
 * tenant filter has been set. Setting only the first is the trap {@link TenantScopeExecutor}'s
 * javadoc describes: inserts would be stamped with the right {@code client_id} while every query
 * in the same block still carried no predicate and silently returned nothing. Going through the
 * executor moves both together and restores them exactly.
 *
 * <p>Harmless on the synchronous path, where it re-points the filter at the tenant it is already
 * pointed at - which is why both paths can share one method instead of branching.
 */
@Component
@RequiredArgsConstructor
public class ImportCommitWorker {

    private final ImportSessionService importSessionService;
    private final TenantScopeExecutor tenantScopeExecutor;

    @Transactional
    public void run(ImportSessionService.CommitTicket ticket) {
        tenantScopeExecutor.runAs(ticket.clientId(), () -> importSessionService.runCommit(ticket));
    }
}
