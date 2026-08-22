package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.AddressPurpose;
import com.procurepal_services.stock_bridge_api.entity.DeliveryAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every finder here takes an {@link AddressPurpose}, and none of the
 * purpose-blind versions survive.
 *
 * <p>That is deliberate rather than tidy-minded. This table holds two different
 * facts since V13 - where a buyer wants goods delivered, and where a seller's
 * goods are collected from - and both can belong to the SAME tenant (ProcurePal
 * is a seller and an ordinary buying company). A finder without the predicate
 * therefore compiles, passes tenant isolation, and quietly offers a depot as a
 * delivery destination at checkout. Deleting the old signatures rather than
 * leaving them alongside the new ones means that mistake cannot be made by
 * autocomplete; see {@link AddressPurpose} for the obligation in full.
 */
public interface DeliveryAddressRepository extends TenantScopedRepository<DeliveryAddress, UUID> {

    /** Default first, then alphabetical - the order the checkout address picker renders. */
    List<DeliveryAddress> findAllByClientIdAndPurposeAndActiveTrueOrderByDefaultAddressDescLabelAsc(
            UUID clientId, AddressPurpose purpose);

    Optional<DeliveryAddress> findByClientIdAndPurposeAndDefaultAddressTrueAndActiveTrue(
            UUID clientId, AddressPurpose purpose);

    Optional<DeliveryAddress> findByIdAndClientIdAndPurposeAndActiveTrue(
            UUID id, UUID clientId, AddressPurpose purpose);

    long countByClientIdAndPurposeAndActiveTrue(UUID clientId, AddressPurpose purpose);

    /**
     * Clears the default flag across a client's addresses OF ONE PURPOSE. Promoting
     * a new default has to demote the old one in the same transaction, or the
     * partial unique index (one default per active client per purpose) rejects the
     * insert - doing it as one bulk UPDATE avoids a load-modify-save loop that
     * would trip the constraint mid-flight depending on flush order.
     *
     * <p>The purpose predicate is what stops a seller saving a pickup point from
     * demoting the delivery address its own checkout would have used. V13 split the
     * index for the same reason; this statement and that index have to agree.
     */
    @Modifying
    @Query("UPDATE DeliveryAddress a SET a.defaultAddress = false "
            + "WHERE a.clientId = :clientId AND a.purpose = :purpose AND a.defaultAddress = true")
    int clearDefaultForClient(@Param("clientId") UUID clientId, @Param("purpose") AddressPurpose purpose);
}
