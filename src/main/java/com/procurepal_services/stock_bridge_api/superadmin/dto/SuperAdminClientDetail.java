package com.procurepal_services.stock_bridge_api.superadmin.dto;

import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * GET /api/superadmin/clients/{id}, and the response to both writes on that row
 * (PUT /{id} and PUT /{id}/status). activeUserCount/activeProductCount/
 * lowStockProductCount are the "quick-glance" numbers, distinct from the
 * plain userCount/productCount totals also shown in the list view.
 *
 * <h2>Why the write endpoints answer with this rather than a leaner record</h2>
 * One response type for the whole per-client surface means an ops screen never
 * has to decide which shape it just received, and - more usefully - every write
 * echoes back the fields it was NOT allowed to change (see UpdateClientRequest
 * for that list). A caller that tried to smuggle {@code isPlatformOwner} or
 * {@code isActive} into the body reads the real value in the same response, so
 * the answer is "no", not silence. Recomputing the counts on a write costs three
 * aggregate queries against a row somebody is looking at interactively, which is
 * not a price worth optimising away for the clarity it buys.
 *
 * <h2>slug, not clientIdentifier</h2>
 * The tenant-facing CompanyResponse calls this same column {@code clientIdentifier},
 * matching what the login form calls it. This surface has called it {@code slug}
 * since it shipped (SuperAdminClientSummary does too) and the super-admin frontend
 * already reads that name, so renaming it here to match would break a live consumer
 * for cosmetics. The divergence is deliberate, and this is the only field where the
 * two surfaces disagree - every other name (name, adminEmail, phone, paymentTerms,
 * active, platformOwner) is identical on both. If they are ever unified, do it as a
 * versioned change to both at once rather than quietly here.
 *
 * <h2>Appending components is safe; reordering them is not</h2>
 * The three fields below were added after this record shipped. That is a
 * backwards-compatible change for JSON consumers - existing readers ignore keys
 * they do not know - but it is a source-breaking one for any canonical-constructor
 * call, which is why the only construction site is SuperAdminClientService.toDetail.
 * Keep it that way, and append rather than insert.
 */
public record SuperAdminClientDetail(
        UUID id,
        String name,
        String slug,
        boolean active,
        String adminEmail,
        long userCount,
        long productCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long activeUserCount,
        long activeProductCount,
        long lowStockProductCount,
        String phone,
        // Reported, never accepted - see UpdateClientRequest. platformOwner is
        // also how an ops screen knows this row is ProcurePal itself and can offer
        // the platform-owner user management that no other tenant gets.
        boolean platformOwner,
        PaymentTerms paymentTerms) {
}
