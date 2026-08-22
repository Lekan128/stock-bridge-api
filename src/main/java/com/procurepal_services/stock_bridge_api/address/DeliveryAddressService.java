package com.procurepal_services.stock_bridge_api.address;

import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressRequest;
import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressResponse;
import com.procurepal_services.stock_bridge_api.entity.AddressPurpose;
import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.entity.DeliveryAddress;
import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.repository.DeliveryAddressRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A company's address book (contract §4.3). Tenant-scoped twice over: the
 * Hibernate filter plus explicit client_id predicates on every finder.
 *
 * <h2>Two kinds of address, one set of mechanics</h2>
 * Since V13 this table holds delivery addresses AND sellers' pickup points - see
 * {@link AddressPurpose} for why they share a table. The mechanics below (one
 * default, first-one-wins, soft delete, Nigerian-state validation) are identical
 * for both, so they are written once and every method takes the purpose as its
 * first argument. The no-purpose overloads are {@link AddressPurpose#DELIVERY}
 * shorthands for the buyer surface, which is the only caller that had them
 * before and the only one that should be able to omit the argument: forgetting
 * it there yields the buyer's address book, which is what it wanted anyway.
 * {@code VendorPickupAddressService} passes PICKUP explicitly, every time.
 *
 * <p>The purpose is not merely a filter on reads. It is written on create,
 * carried through {@code demoteExistingDefault} (so promoting a pickup point
 * cannot demote the delivery address checkout would have chosen), and required
 * on every lookup - which is what makes an id from the other half of the same
 * tenant's book read as "not found" rather than being edited under the wrong
 * heading.
 *
 * <h2>Delete is a soft delete, always</h2>
 * Orders snapshot the address they shipped to, but they also keep
 * {@code delivery_address_id} so "reuse this address" and per-location analytics can
 * point back at the original row. Hard-deleting would either break that pointer or
 * cascade-null it, and in both cases a company's delivery history quietly loses
 * information about a place it really did ship to. Deactivating costs one boolean
 * and keeps the past intact, so it is unconditional rather than conditional on
 * whether an order happens to reference the row today - a rule with an exception is
 * a rule someone will get wrong later.
 *
 * <h2>Exactly one default, per purpose</h2>
 * Enforced by a partial unique index over (client_id, address_purpose) WHERE
 * is_default AND is_active. Promoting a new default therefore has to demote the old
 * one in the SAME transaction, and as a bulk UPDATE flushed before the promotion - a
 * load-modify-save pair can flush in either order and trip the index halfway through.
 */
@Service
@RequiredArgsConstructor
public class DeliveryAddressService {

    private final DeliveryAddressRepository deliveryAddressRepository;
    private final BranchRepository branchRepository;

    // ------------------------------------------------------------------------
    // The buyer's address book. DELIVERY, always.
    // ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DeliveryAddressResponse> list() {
        return list(AddressPurpose.DELIVERY);
    }

    @Transactional(readOnly = true)
    public DeliveryAddressResponse get(UUID id) {
        return get(AddressPurpose.DELIVERY, id);
    }

    @Transactional
    public DeliveryAddressResponse create(DeliveryAddressRequest request) {
        return create(AddressPurpose.DELIVERY, request);
    }

    @Transactional
    public DeliveryAddressResponse update(UUID id, DeliveryAddressRequest request) {
        return update(AddressPurpose.DELIVERY, id, request);
    }

    @Transactional
    public void deactivate(UUID id) {
        deactivate(AddressPurpose.DELIVERY, id);
    }

    @Transactional
    public DeliveryAddressResponse makeDefault(UUID id) {
        return makeDefault(AddressPurpose.DELIVERY, id);
    }

    // ------------------------------------------------------------------------
    // The mechanics, for either purpose.
    // ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DeliveryAddressResponse> list(AddressPurpose purpose) {
        return deliveryAddressRepository
                .findAllByClientIdAndPurposeAndActiveTrueOrderByDefaultAddressDescLabelAsc(requireTenantId(), purpose)
                .stream()
                .map(DeliveryAddressResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeliveryAddressResponse get(AddressPurpose purpose, UUID id) {
        return DeliveryAddressResponse.from(requireAddress(purpose, id));
    }

    @Transactional
    public DeliveryAddressResponse create(AddressPurpose purpose, DeliveryAddressRequest request) {
        UUID clientId = requireTenantId();
        validate(request);

        // The first address a company saves becomes the default whether they asked or
        // not: an address book with no default makes checkout ask a question that has
        // exactly one possible answer. Counted within the purpose, so a seller's first
        // pickup point is its default pickup point even though it already has delivery
        // addresses.
        boolean firstAddress = deliveryAddressRepository.countByClientIdAndPurposeAndActiveTrue(clientId, purpose) == 0;
        boolean makeDefault = firstAddress || Boolean.TRUE.equals(request.makeDefault());
        if (makeDefault) {
            demoteExistingDefault(clientId, purpose);
        }

        DeliveryAddress address = DeliveryAddress.builder()
                .purpose(purpose)
                .label(request.label().trim())
                .contactName(request.contactName().trim())
                .contactPhone(request.contactPhone().trim())
                .addressLine1(request.addressLine1().trim())
                .addressLine2(blankToNull(request.addressLine2()))
                .city(request.city().trim())
                .state(request.state().trim())
                .landmark(blankToNull(request.landmark()))
                .deliveryNotes(blankToNull(request.deliveryNotes()))
                .branch(resolveBranch(request.branchId(), clientId))
                .defaultAddress(makeDefault)
                .active(true)
                .build();
        return DeliveryAddressResponse.from(deliveryAddressRepository.saveAndFlush(address));
    }

    @Transactional
    public DeliveryAddressResponse update(AddressPurpose purpose, UUID id, DeliveryAddressRequest request) {
        UUID clientId = requireTenantId();
        validate(request);
        DeliveryAddress address = requireAddress(purpose, id);

        if (Boolean.TRUE.equals(request.makeDefault()) && !address.isDefaultAddress()) {
            demoteExistingDefault(clientId, purpose);
            address.setDefaultAddress(true);
        }
        address.setLabel(request.label().trim());
        address.setContactName(request.contactName().trim());
        address.setContactPhone(request.contactPhone().trim());
        address.setAddressLine1(request.addressLine1().trim());
        address.setAddressLine2(blankToNull(request.addressLine2()));
        address.setCity(request.city().trim());
        address.setState(request.state().trim());
        address.setLandmark(blankToNull(request.landmark()));
        address.setDeliveryNotes(blankToNull(request.deliveryNotes()));
        address.setBranch(resolveBranch(request.branchId(), clientId));
        // The purpose is deliberately NOT patchable. A row's kind is decided by the
        // surface that created it; letting a request move one across would turn an
        // address a buyer still ships to into a pickup point, or vice versa, with an
        // order already pointing at it.
        return DeliveryAddressResponse.from(deliveryAddressRepository.saveAndFlush(address));
    }

    @Transactional
    public void deactivate(AddressPurpose purpose, UUID id) {
        UUID clientId = requireTenantId();
        DeliveryAddress address = requireAddress(purpose, id);
        address.setActive(false);
        // Dropping the default flag as it goes is what keeps the partial unique index
        // satisfiable: the index covers is_default AND is_active, so a deactivated row
        // that kept the flag would not block a replacement - but leaving it set would
        // resurrect a default if the row were ever reactivated.
        address.setDefaultAddress(false);
        deliveryAddressRepository.saveAndFlush(address);

        // Never leave a company with addresses but no default; checkout would have to
        // invent a fallback, and inventing one silently is how goods go to the wrong
        // warehouse. Scoped to the purpose, so removing a pickup point never promotes
        // a delivery address into being the default pickup point.
        List<DeliveryAddress> remaining = deliveryAddressRepository
                .findAllByClientIdAndPurposeAndActiveTrueOrderByDefaultAddressDescLabelAsc(clientId, purpose);
        if (!remaining.isEmpty() && remaining.stream().noneMatch(DeliveryAddress::isDefaultAddress)) {
            remaining.getFirst().setDefaultAddress(true);
        }
    }

    @Transactional
    public DeliveryAddressResponse makeDefault(AddressPurpose purpose, UUID id) {
        UUID clientId = requireTenantId();
        DeliveryAddress address = requireAddress(purpose, id);
        if (!address.isDefaultAddress()) {
            demoteExistingDefault(clientId, purpose);
            address.setDefaultAddress(true);
        }
        return DeliveryAddressResponse.from(deliveryAddressRepository.saveAndFlush(address));
    }

    /**
     * Checkout's address resolution: explicit id if given, otherwise the company
     * default.
     *
     * <p>Pinned to DELIVERY, and that pin is the one in this class most worth not
     * losing. Without it a buyer could pass the id of a pickup point - their own, if
     * their company also sells - and have goods routed to a depot they collect from.
     */
    @Transactional(readOnly = true)
    public Optional<DeliveryAddress> resolveForCheckout(UUID addressId) {
        UUID clientId = requireTenantId();
        return addressId == null
                ? deliveryAddressRepository.findByClientIdAndPurposeAndDefaultAddressTrueAndActiveTrue(
                        clientId, AddressPurpose.DELIVERY)
                : deliveryAddressRepository.findByIdAndClientIdAndPurposeAndActiveTrue(
                        addressId, clientId, AddressPurpose.DELIVERY);
    }

    /** Used by checkout when the buyer typed a new address inline and asked to keep it. */
    @Transactional
    public DeliveryAddress createEntity(DeliveryAddressRequest request) {
        UUID id = create(AddressPurpose.DELIVERY, request).id();
        return requireAddress(AddressPurpose.DELIVERY, id);
    }

    private void demoteExistingDefault(UUID clientId, AddressPurpose purpose) {
        if (deliveryAddressRepository.clearDefaultForClient(clientId, purpose) > 0) {
            // Force the demotion to hit the database before the promotion is flushed;
            // otherwise Hibernate is free to order the INSERT/UPDATE the other way and
            // the partial unique index rejects a state that is only momentarily invalid.
            deliveryAddressRepository.flush();
        }
    }

    private DeliveryAddress requireAddress(AddressPurpose purpose, UUID id) {
        return deliveryAddressRepository
                .findByIdAndClientIdAndPurposeAndActiveTrue(id, requireTenantId(), purpose)
                .orElseThrow(DeliveryAddressNotFoundException::new);
    }

    private Branch resolveBranch(UUID branchId, UUID clientId) {
        if (branchId == null) {
            return null;
        }
        return branchRepository
                .findByIdAndClientId(branchId, clientId)
                .orElseThrow(() -> new InvalidDeliveryAddressException("That branch was not found."));
    }

    private static void validate(DeliveryAddressRequest request) {
        if (!NigerianStates.isValid(request.state())) {
            throw new InvalidDeliveryAddressException(
                    "\"" + request.state() + "\" is not a Nigerian state. ProcurePal delivers within Nigeria only.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
