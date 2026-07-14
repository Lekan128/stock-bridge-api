package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.product.ProductNotFoundException;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.StockAdjustmentRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every mutating method here locks the product row (ProductRepository's
 * PESSIMISTIC_WRITE query) before reading quantity_on_hand, so a concurrent
 * stock-in/out/adjustment against the same product blocks instead of racing
 * on a read-modify-write - the second transaction sees the first one's
 * committed quantity once its lock is granted, never a stale value. This was
 * chosen over an atomic `UPDATE ... WHERE quantity_on_hand >= ?` because the
 * product row needs to be loaded as a managed entity anyway (to return it in
 * the response, and to update it via the same Hibernate Session that writes
 * the ledger row in one transaction) - a lock on that same read is simpler
 * than a separate conditional-update statement plus a follow-up read, for
 * inventory volumes where lock contention isn't a real throughput concern.
 */
@Service
@RequiredArgsConstructor
public class StockManagementService {

    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final UserRepository userRepository;

    @Transactional
    public StockMutationResponse stockIn(UUID productId, StockInRequest request, UUID actingUserId) {
        Product product = lockProductOrThrow(productId);
        product.setQuantityOnHand(product.getQuantityOnHand() + request.quantity());

        StockMovement movement = stockMovementRepository.save(StockMovement.builder()
                .product(product)
                .movementType(MovementType.IN)
                .quantity(request.quantity())
                .unitPriceAtTime(request.unitPrice())
                .note(request.note())
                .createdBy(reference(actingUserId))
                .build());

        return StockMutationResponse.of(product, movement);
    }

    @Transactional
    public StockMutationResponse stockOut(UUID productId, StockOutRequest request, UUID actingUserId) {
        Product product = lockProductOrThrow(productId);
        if (request.quantity() > product.getQuantityOnHand()) {
            throw new InsufficientStockException(product.getQuantityOnHand(), request.quantity());
        }
        product.setQuantityOnHand(product.getQuantityOnHand() - request.quantity());

        StockMovement movement = stockMovementRepository.save(StockMovement.builder()
                .product(product)
                .movementType(MovementType.OUT)
                .quantity(request.quantity())
                .unitPriceAtTime(request.unitPrice())
                .note(request.note())
                .createdBy(reference(actingUserId))
                .build());

        return StockMutationResponse.of(product, movement);
    }

    /**
     * A newQuantity equal to the current quantity_on_hand is a valid request
     * (the user re-submitted the count they already saw) but represents no
     * actual change, so it's a no-op: no ledger row is written (there'd be
     * nothing to audit, and quantity=0 isn't a valid ADJUSTMENT row - see
     * V4__relax_stock_movements_quantity_constraint.sql) and the response
     * simply echoes the unchanged product back.
     */
    @Transactional
    public StockMutationResponse adjust(UUID productId, StockAdjustmentRequest request, UUID actingUserId) {
        Product product = lockProductOrThrow(productId);
        int delta = request.newQuantity() - product.getQuantityOnHand();
        if (delta == 0) {
            return StockMutationResponse.of(product, null);
        }
        product.setQuantityOnHand(request.newQuantity());

        StockMovement movement = stockMovementRepository.save(StockMovement.builder()
                .product(product)
                .movementType(MovementType.ADJUSTMENT)
                .quantity(delta)
                .note(request.note())
                .createdBy(reference(actingUserId))
                .build());

        return StockMutationResponse.of(product, movement);
    }

    @Transactional(readOnly = true)
    public Page<StockMovementResponse> history(UUID productId, Pageable pageable) {
        UUID tenantId = requireTenantId();
        if (productRepository.findByIdAndClientId(productId, tenantId).isEmpty()) {
            throw new ProductNotFoundException();
        }
        return stockMovementRepository
                .findAll(StockMovementSpecifications.forTenant(tenantId, productId, null, null, null), pageable)
                .map(StockMovementResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<StockMovementResponse> allMovements(
            OffsetDateTime from, OffsetDateTime to, MovementType movementType, Pageable pageable) {
        return stockMovementRepository
                .findAll(StockMovementSpecifications.forTenant(requireTenantId(), null, from, to, movementType), pageable)
                .map(StockMovementResponse::from);
    }

    private Product lockProductOrThrow(UUID productId) {
        return productRepository
                .findByIdAndClientIdForUpdate(productId, requireTenantId())
                .orElseThrow(ProductNotFoundException::new);
    }

    private User reference(UUID userId) {
        return userId == null ? null : userRepository.getReferenceById(userId);
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
