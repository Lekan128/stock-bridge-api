package com.procurepal_services.stock_bridge_api.vendor.pickup;

import com.procurepal_services.stock_bridge_api.address.DeliveryAddressService;
import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressRequest;
import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressResponse;
import com.procurepal_services.stock_bridge_api.entity.AddressPurpose;
import com.procurepal_services.stock_bridge_api.vendor.VendorGuard;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a seller's goods are collected FROM - the stakeholder's "manage pickup
 * addresses", and the mirror image of {@code DeliveryAddressController}.
 *
 * <h2>Two gates, the usual pair</h2>
 * {@code @PreAuthorize("hasAuthority('MANAGE_DELIVERY_ADDRESSES')")} proves the
 * caller does address work - the VENDOR role holds that code, granted by V11 for
 * exactly this screen, and so does every tenant's OWNER, which is why it proves
 * nothing on its own. {@link VendorGuard#requireSeller()} proves their company
 * sells. {@code requireSeller} and not {@code requireVendor}: ProcurePal ships
 * from its own warehouses and has pickup points like any other seller, and
 * refusing it here would be the same mistake as putting requireVendor on the
 * fulfilment queue.
 *
 * <h2>Why these are not the same rows as /api/delivery-addresses</h2>
 * They live in the same table and are told apart by
 * {@link AddressPurpose} - see that enum for why one table. What matters here is
 * that this controller never passes anything but PICKUP and the buyer controller
 * never passes anything but DELIVERY, so neither surface can serve the other's
 * rows even for a tenant that has both. ProcurePal is exactly that tenant, which
 * is what makes the separation load-bearing rather than theoretical.
 *
 * <h2>Why the DTOs are the delivery ones, unchanged</h2>
 * A pickup point and a delivery address are the same ten fields with the same
 * validation, including the Nigerian-state check that has to happen server-side.
 * A parallel PickupAddressRequest/Response pair would be a copy that drifts -
 * the first time a field is added to one and not the other, the same frontend
 * address controls stop working on one of the two screens. The one field whose
 * name reads oddly here is {@code deliveryNotes}, which a vendor uses for
 * collection instructions; renaming it on the wire would mean a second mapping
 * to keep in step, which costs more than the awkward name.
 *
 * <p>The 403 needs no advice here - VendorAccessExceptionHandler is global - and
 * the 404/400 come from the address package's own advice.
 */
@RestController
@RequestMapping("/api/vendor/pickup-addresses")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_DELIVERY_ADDRESSES')")
public class VendorPickupAddressController {

    private final DeliveryAddressService deliveryAddressService;
    private final VendorGuard vendorGuard;

    @GetMapping
    public List<DeliveryAddressResponse> list() {
        vendorGuard.requireSeller();
        return deliveryAddressService.list(AddressPurpose.PICKUP);
    }

    @GetMapping("/{id}")
    public DeliveryAddressResponse get(@PathVariable UUID id) {
        vendorGuard.requireSeller();
        return deliveryAddressService.get(AddressPurpose.PICKUP, id);
    }

    @PostMapping
    public DeliveryAddressResponse create(@Valid @RequestBody DeliveryAddressRequest request) {
        vendorGuard.requireSeller();
        return deliveryAddressService.create(AddressPurpose.PICKUP, request);
    }

    @PutMapping("/{id}")
    public DeliveryAddressResponse update(
            @PathVariable UUID id, @Valid @RequestBody DeliveryAddressRequest request) {
        vendorGuard.requireSeller();
        return deliveryAddressService.update(AddressPurpose.PICKUP, id, request);
    }

    /** A deactivation, exactly as on the buyer surface - see DeliveryAddressService. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        vendorGuard.requireSeller();
        deliveryAddressService.deactivate(AddressPurpose.PICKUP, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/default")
    public DeliveryAddressResponse makeDefault(@PathVariable UUID id) {
        vendorGuard.requireSeller();
        return deliveryAddressService.makeDefault(AddressPurpose.PICKUP, id);
    }
}
