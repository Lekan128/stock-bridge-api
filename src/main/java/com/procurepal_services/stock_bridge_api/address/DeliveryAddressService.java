package com.procurepal_services.stock_bridge_api.address;

import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressRequest;
import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressResponse;
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
 * A company's delivery locations (contract §4.3). Tenant-scoped twice over: the
 * Hibernate filter plus explicit client_id predicates on every finder.
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
 * <h2>Exactly one default</h2>
 * Enforced by a partial unique index over (client_id) WHERE is_default AND is_active.
 * Promoting a new default therefore has to demote the old one in the SAME
 * transaction, and as a bulk UPDATE flushed before the promotion - a load-modify-save
 * pair can flush in either order and trip the index halfway through.
 */
@Service
@RequiredArgsConstructor
public class DeliveryAddressService {

    private final DeliveryAddressRepository deliveryAddressRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public List<DeliveryAddressResponse> list() {
        return deliveryAddressRepository
                .findAllByClientIdAndActiveTrueOrderByDefaultAddressDescLabelAsc(requireTenantId())
                .stream()
                .map(DeliveryAddressResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeliveryAddressResponse get(UUID id) {
        return DeliveryAddressResponse.from(requireAddress(id));
    }

    @Transactional
    public DeliveryAddressResponse create(DeliveryAddressRequest request) {
        UUID clientId = requireTenantId();
        validate(request);

        // The first address a company saves becomes the default whether they asked or
        // not: an address book with no default makes checkout ask a question that has
        // exactly one possible answer.
        boolean firstAddress = deliveryAddressRepository.countByClientIdAndActiveTrue(clientId) == 0;
        boolean makeDefault = firstAddress || Boolean.TRUE.equals(request.makeDefault());
        if (makeDefault) {
            demoteExistingDefault(clientId);
        }

        DeliveryAddress address = DeliveryAddress.builder()
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
    public DeliveryAddressResponse update(UUID id, DeliveryAddressRequest request) {
        UUID clientId = requireTenantId();
        validate(request);
        DeliveryAddress address = requireAddress(id);

        if (Boolean.TRUE.equals(request.makeDefault()) && !address.isDefaultAddress()) {
            demoteExistingDefault(clientId);
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
        return DeliveryAddressResponse.from(deliveryAddressRepository.saveAndFlush(address));
    }

    @Transactional
    public void deactivate(UUID id) {
        UUID clientId = requireTenantId();
        DeliveryAddress address = requireAddress(id);
        address.setActive(false);
        // Dropping the default flag as it goes is what keeps the partial unique index
        // satisfiable: the index covers is_default AND is_active, so a deactivated row
        // that kept the flag would not block a replacement - but leaving it set would
        // resurrect a default if the row were ever reactivated.
        address.setDefaultAddress(false);
        deliveryAddressRepository.saveAndFlush(address);

        // Never leave a company with addresses but no default; checkout would have to
        // invent a fallback, and inventing one silently is how goods go to the wrong
        // warehouse.
        List<DeliveryAddress> remaining =
                deliveryAddressRepository.findAllByClientIdAndActiveTrueOrderByDefaultAddressDescLabelAsc(clientId);
        if (!remaining.isEmpty() && remaining.stream().noneMatch(DeliveryAddress::isDefaultAddress)) {
            remaining.getFirst().setDefaultAddress(true);
        }
    }

    @Transactional
    public DeliveryAddressResponse makeDefault(UUID id) {
        UUID clientId = requireTenantId();
        DeliveryAddress address = requireAddress(id);
        if (!address.isDefaultAddress()) {
            demoteExistingDefault(clientId);
            address.setDefaultAddress(true);
        }
        return DeliveryAddressResponse.from(deliveryAddressRepository.saveAndFlush(address));
    }

    /** Checkout's address resolution: explicit id if given, otherwise the company default. */
    @Transactional(readOnly = true)
    public Optional<DeliveryAddress> resolveForCheckout(UUID addressId) {
        UUID clientId = requireTenantId();
        return addressId == null
                ? deliveryAddressRepository.findByClientIdAndDefaultAddressTrueAndActiveTrue(clientId)
                : deliveryAddressRepository.findByIdAndClientIdAndActiveTrue(addressId, clientId);
    }

    /** Used by checkout when the buyer typed a new address inline and asked to keep it. */
    @Transactional
    public DeliveryAddress createEntity(DeliveryAddressRequest request) {
        UUID id = create(request).id();
        return requireAddress(id);
    }

    private void demoteExistingDefault(UUID clientId) {
        if (deliveryAddressRepository.clearDefaultForClient(clientId) > 0) {
            // Force the demotion to hit the database before the promotion is flushed;
            // otherwise Hibernate is free to order the INSERT/UPDATE the other way and
            // the partial unique index rejects a state that is only momentarily invalid.
            deliveryAddressRepository.flush();
        }
    }

    private DeliveryAddress requireAddress(UUID id) {
        return deliveryAddressRepository
                .findByIdAndClientIdAndActiveTrue(id, requireTenantId())
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
