package com.procurepal_services.stock_bridge_api.purchase;

import com.procurepal_services.stock_bridge_api.companyvendor.CompanyVendorLookup;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.purchase.dto.PurchaseHistoryEntry;
import com.procurepal_services.stock_bridge_api.purchase.dto.PurchaseSource;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Everything this company has bought" - the company-wide screen above the per-vendor one on
 * {@code CompanyVendorController}. Both are the same question with a different filter, which is
 * why there is exactly one implementation: {@link #search} with {@code companyVendorId} null is
 * the company-wide answer, and non-null is the same answer narrowed to one supplier.
 *
 * <h2>Pointer, then batch-hydrate</h2>
 * {@link PurchaseHistoryRepository} resolves the correctly-paginated, correctly-sorted (id,
 * source) list for this page - see its own javadoc for why that has to be a single native query.
 * This class turns that pointer list into full {@link PurchaseHistoryEntry} rows with exactly
 * three follow-up queries regardless of page size: one for the orders on this page (with their
 * items), one for the stock movements on this page, one for the vendors named on either. Assembly
 * then walks the pointer list once, in its already-correct order, and looks each row up in the
 * two maps built from those queries - so the expensive part (the union, the sort, the limit) runs
 * once in the database and the rest is O(page size) in memory.
 */
@Service
@RequiredArgsConstructor
public class PurchaseHistoryService {

    private final PurchaseHistoryRepository purchaseHistoryRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CompanyVendorRepository companyVendorRepository;
    private final CompanyVendorLookup companyVendorLookup;

    @Transactional(readOnly = true)
    public Page<PurchaseHistoryEntry> search(
            UUID companyVendorId, PurchaseSource source, OffsetDateTime from, OffsetDateTime to, Pageable pageable) {
        UUID buyerClientId = requireTenantId();
        if (companyVendorId != null) {
            // 404s if this vendor is not this tenant's own - same rule every vendor-id-taking
            // endpoint follows, so a probed id from another company never distinguishes
            // "exists" from "not yours".
            companyVendorLookup.require(companyVendorId);
        }

        PurchaseHistoryFilter filter = new PurchaseHistoryFilter(buyerClientId, companyVendorId, source, from, to);
        long total = purchaseHistoryRepository.count(filter);
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<PurchasePointer> pointers =
                purchaseHistoryRepository.findPointers(filter, pageable.getPageSize(), pageable.getOffset());

        List<UUID> orderIds = pointers.stream()
                .filter(p -> p.source() == PurchaseSource.MARKETPLACE_ORDER)
                .map(PurchasePointer::id)
                .toList();
        List<UUID> movementIds = pointers.stream()
                .filter(p -> p.source() == PurchaseSource.MANUAL_STOCK_IN)
                .map(PurchasePointer::id)
                .toList();

        Map<UUID, Order> ordersById =
                orderIds.isEmpty() ? Map.of() : orderRepository.findAllById(orderIds).stream()
                        .collect(Collectors.toMap(Order::getId, order -> order));
        Map<UUID, List<OrderItem>> itemsByOrderId = orderIds.isEmpty()
                ? Map.of()
                : orderItemRepository.findAllByOrderIdInOrderByCreatedAtAsc(orderIds).stream()
                        .collect(Collectors.groupingBy(item -> item.getOrder().getId()));
        Map<UUID, StockMovement> movementsById =
                movementIds.isEmpty() ? Map.of() : stockMovementRepository.findAllById(movementIds).stream()
                        .collect(Collectors.toMap(StockMovement::getId, m -> m));

        List<UUID> vendorIds = pointers.stream()
                .map(PurchasePointer::companyVendorId)
                .distinct()
                .toList();
        Map<UUID, String> vendorNameById = vendorIds.isEmpty() ? Map.of() : companyVendorRepository.findAllById(vendorIds).stream()
                .collect(Collectors.toMap(CompanyVendor::getId, CompanyVendor::getName));

        List<PurchaseHistoryEntry> entries = new ArrayList<>(pointers.size());
        for (PurchasePointer pointer : pointers) {
            String vendorName = vendorNameById.get(pointer.companyVendorId());
            if (pointer.source() == PurchaseSource.MARKETPLACE_ORDER) {
                Order order = ordersById.get(pointer.id());
                if (order == null) {
                    continue;
                }
                entries.add(PurchaseHistoryEntry.fromOrder(
                        order,
                        itemsByOrderId.getOrDefault(order.getId(), List.of()),
                        pointer.companyVendorId(),
                        vendorName));
            } else {
                StockMovement movement = movementsById.get(pointer.id());
                if (movement == null) {
                    continue;
                }
                entries.add(PurchaseHistoryEntry.fromStockMovement(movement, vendorName));
            }
        }

        return new PageImpl<>(entries, pageable, total);
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
