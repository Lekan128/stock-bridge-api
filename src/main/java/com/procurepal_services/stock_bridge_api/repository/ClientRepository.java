package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.Client;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClientRepository extends JpaRepository<Client, UUID>, JpaSpecificationExecutor<Client> {

    Optional<Client> findBySlug(String slug);

    long countByActiveTrue();

    /**
     * ProcurePal. At most one row can match (partial unique index on
     * is_platform_owner), so the Optional is "has the platform owner been seeded
     * yet", not "which one".
     *
     * Prefer PlatformOwnerGuard over calling this directly for authorization -
     * the guard is where the 403 behaviour is defined. This finder is for the
     * public catalog, which needs the platform owner's client_id as a filter and
     * must degrade to an empty catalog (not an error) if no platform owner exists.
     */
    Optional<Client> findByPlatformOwnerTrue();

    long countByPlatformOwnerTrue();

    /**
     * Does any company claim this address as its contact of record?
     *
     * Read by EmailEligibility, which needs it because most email this application
     * sends goes to clients.admin_contact_email rather than to a users row - see
     * that class for the policy this answer feeds.
     *
     * Unlike the equivalents on UserRepository, this is not native for tenancy
     * reasons - Client is not a TenantAwareEntity and carries no Hibernate tenant
     * filter, so a derived query would already read across tenants exactly as
     * PlatformOwnerGuard and EmailRecipients.forClient do. It is native so the
     * predicate is literally lower(admin_contact_email) = ?, which is the
     * expression V8's functional index is built on. The obvious derived form,
     * existsByAdminContactEmailIgnoreCase, generates UPPER(...) = UPPER(?)
     * instead and would silently miss that index - turning a per-recipient lookup
     * on the checkout path into a sequential scan of every company on the
     * platform.
     *
     * Returns a count rather than a boolean because a native query cannot bind to
     * a primitive boolean reliably across drivers; callers read it as "> 0".
     * Address must arrive already lowercased and trimmed.
     */
    @Query(
            value = "SELECT count(*) FROM clients WHERE lower(admin_contact_email) = :address",
            nativeQuery = true)
    long countByLowercasedAdminContactEmail(@Param("address") String address);
}
