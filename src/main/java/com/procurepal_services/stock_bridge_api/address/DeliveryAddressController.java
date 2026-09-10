package com.procurepal_services.stock_bridge_api.address;

import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressRequest;
import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressResponse;
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
 * A company's DELIVERY locations - where it wants goods sent.
 *
 * <p>Every route here is pinned to {@code AddressPurpose.DELIVERY}, so a seller's
 * pickup points never appear on this surface even when they belong to the same
 * tenant (ProcurePal is both). The pickup half lives at
 * {@code /api/vendor/pickup-addresses}; the two share a table, a service and a
 * validator, and differ only in the purpose each passes. See {@code AddressPurpose}.
 *
 * <p>DELETE is a deactivation, not a removal - see DeliveryAddressService for why -
 * so it answers 204 and the row stops appearing in the list, which is
 * indistinguishable from a delete for every caller.
 */
@RestController
@RequestMapping("/api/delivery-addresses")
@RequiredArgsConstructor
public class DeliveryAddressController {

    private final DeliveryAddressService deliveryAddressService;

    /**
     * Readable by anyone who can place an order or see the company's purchases, not
     * just by whoever maintains the address book - the checkout address picker and
     * an order's shipping details both need it.
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('MANAGE_DELIVERY_ADDRESSES','PLACE_ORDERS','VIEW_ORDERS')")
    public List<DeliveryAddressResponse> list() {
        return deliveryAddressService.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('MANAGE_DELIVERY_ADDRESSES','PLACE_ORDERS','VIEW_ORDERS')")
    public DeliveryAddressResponse get(@PathVariable UUID id) {
        return deliveryAddressService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MANAGE_DELIVERY_ADDRESSES')")
    public DeliveryAddressResponse create(@Valid @RequestBody DeliveryAddressRequest request) {
        return deliveryAddressService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_DELIVERY_ADDRESSES')")
    public DeliveryAddressResponse update(
            @PathVariable UUID id, @Valid @RequestBody DeliveryAddressRequest request) {
        return deliveryAddressService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_DELIVERY_ADDRESSES')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        deliveryAddressService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/default")
    @PreAuthorize("hasAuthority('MANAGE_DELIVERY_ADDRESSES')")
    public DeliveryAddressResponse makeDefault(@PathVariable UUID id) {
        return deliveryAddressService.makeDefault(id);
    }
}
