package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.StockTransfer;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockTransferRepository extends TenantScopedRepository<StockTransfer, UUID> {

    /**
     * The waybill, row-locked: two people receiving the same transfer queue here, and the second sees
     * what the first recorded (MULTI_BRANCH_BUILD_PLAN.md section 5.4).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM StockTransfer t WHERE t.id = :id AND t.clientId = :clientId")
    Optional<StockTransfer> findForUpdate(@Param("id") UUID id, @Param("clientId") UUID clientId);

    @Query("SELECT COALESCE(MAX(t.number), 0) FROM StockTransfer t WHERE t.clientId = :clientId")
    int maxNumber(@Param("clientId") UUID clientId);

    /** Transfers to or from any of these branches, newest first - the Transfers list. */
    @Query("SELECT t FROM StockTransfer t WHERE t.clientId = :clientId "
            + "AND (t.fromBranchId IN :branchIds OR t.toBranchId IN :branchIds) "
            + "AND (:openOnly = FALSE OR t.status IN (com.procurepal_services.stock_bridge_api.entity.StockTransferStatus.ON_THE_WAY, "
            + "com.procurepal_services.stock_bridge_api.entity.StockTransferStatus.PARTLY_RECEIVED)) "
            + "ORDER BY t.sentAt DESC, t.number DESC")
    Page<StockTransfer> findTouching(
            @Param("clientId") UUID clientId,
            @Param("branchIds") Collection<UUID> branchIds,
            @Param("openOnly") boolean openOnly,
            Pageable pageable);
}
