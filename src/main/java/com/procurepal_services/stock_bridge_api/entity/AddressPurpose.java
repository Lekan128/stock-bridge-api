package com.procurepal_services.stock_bridge_api.entity;

/**
 * What a {@link DeliveryAddress} row is FOR, stored as its name in
 * {@code delivery_addresses.address_purpose}.
 *
 * <h2>Why one table with a discriminator rather than two tables</h2>
 * A pickup point and a delivery address are the same ten fields - a Nigerian
 * state, a contact name and phone, a landmark, a soft-delete flag, one default -
 * validated by the same rules and rendered by the same frontend controls, and
 * {@code orders.delivery_address_id} already points at this table. V11 reached
 * the same conclusion from the vendor side and said vendors would reuse
 * {@code delivery_addresses} "as-is"; V13 keeps the table and adds the one thing
 * "as-is" was missing. See that migration for the case that forced it, which is
 * ProcurePal rather than any vendor: the platform owner is a seller AND an
 * ordinary buying company, so tenant scoping cannot tell its two kinds of
 * address apart - they belong to the same tenant.
 *
 * <h2>The obligation this enum creates</h2>
 * Exactly like {@code ProductApprovalStatus}, a discriminator is only worth
 * anything if every query carries it. Two places discharge that here and must
 * stay in step: {@code DeliveryAddressService}, which pins every buyer-side read
 * and write to {@link #DELIVERY} (checkout's address picker included), and
 * {@code VendorPickupAddressService}, which pins its own to {@link #PICKUP}.
 * A finder that forgets is not a cross-tenant leak - the client_id predicate
 * still holds - but it does put a depot goods are collected from into a buyer's
 * checkout picker, which is a delivery to the wrong place.
 */
public enum AddressPurpose {

    /** Where this company wants goods delivered TO. The buyer's address book. */
    DELIVERY,

    /** Where a seller's goods are COLLECTED FROM. The vendor's pickup points. */
    PICKUP
}
