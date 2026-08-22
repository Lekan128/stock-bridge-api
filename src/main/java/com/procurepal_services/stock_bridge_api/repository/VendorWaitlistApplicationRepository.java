package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistApplication;
import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The vendor waitlist. A plain JpaRepository, NOT a TenantScopedRepository:
 * VendorWaitlistApplication is not tenant-scoped (an applicant has no tenant yet,
 * and the form is submitted with no TenantContext at all - see that class), so
 * there is nothing to isolate and nothing here needs a client_id predicate.
 *
 * <p>Every read below is a super-admin read. Authorization for it belongs to the
 * super-admin surface, exactly as it does for SuperAdminClientService: this
 * repository is unguarded by design because the entity has no tenant to guard it
 * with, which makes it the caller's job and not a detail anyone can forget
 * quietly.
 */
public interface VendorWaitlistApplicationRepository extends JpaRepository<VendorWaitlistApplication, UUID> {

    /** The review queue: oldest first, because the person who waited longest goes first. */
    Page<VendorWaitlistApplication> findAllByStatusOrderByCreatedAtAsc(
            VendorWaitlistStatus status, Pageable pageable);

    List<VendorWaitlistApplication> findAllByStatusOrderByCreatedAtAsc(VendorWaitlistStatus status);

    Page<VendorWaitlistApplication> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(VendorWaitlistStatus status);

    /**
     * "Has this business already applied?" - a reviewer's aid, not a uniqueness
     * rule. There is deliberately no unique constraint on email: a rejected
     * applicant may reapply with better information, and two unrelated businesses
     * can share a shopfront address. Matching is case-insensitive because an
     * applicant retyping their address will not reproduce its capitalisation.
     */
    List<VendorWaitlistApplication> findAllByEmailIgnoreCaseOrderByCreatedAtDesc(String email);

    /** The application an approved vendor came from, if they came from one at all. */
    Optional<VendorWaitlistApplication> findByApprovedClientId(UUID approvedClientId);
}
