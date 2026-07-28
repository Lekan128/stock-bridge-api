package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.DeliveryAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryAddressRepository extends TenantScopedRepository<DeliveryAddress, UUID> {

    /** Default first, then alphabetical - the order the checkout address picker renders. */
    List<DeliveryAddress> findAllByClientIdAndActiveTrueOrderByDefaultAddressDescLabelAsc(UUID clientId);

    Optional<DeliveryAddress> findByClientIdAndDefaultAddressTrueAndActiveTrue(UUID clientId);

    Optional<DeliveryAddress> findByIdAndClientIdAndActiveTrue(UUID id, UUID clientId);

    long countByClientIdAndActiveTrue(UUID clientId);

    /**
     * Clears the default flag across a client's addresses. Promoting a new default
     * has to demote the old one in the same transaction, or the partial unique
     * index (one default per active client) rejects the insert - doing it as one
     * bulk UPDATE avoids a load-modify-save loop that would trip the constraint
     * mid-flight depending on flush order.
     */
    @Modifying
    @Query("UPDATE DeliveryAddress a SET a.defaultAddress = false "
            + "WHERE a.clientId = :clientId AND a.defaultAddress = true")
    int clearDefaultForClient(@Param("clientId") UUID clientId);
}
