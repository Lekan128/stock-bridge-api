package com.procurepal_services.stock_bridge_api.stock.dto;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.util.List;
import java.util.UUID;

/**
 * Bundles the updated product (isLowStock included) with the movement that
 * caused it, so the frontend can refresh its low-stock banner from this
 * response alone instead of issuing a follow-up GET.
 *
 * <h2>V19: the extra fields are one-directional</h2>
 * {@code vendorIsNewToProduct}/{@code cheaperVendorHint} are only ever populated by {@link
 * #ofStockIn} and {@code breakdown} only by {@link #ofStockOut} - each is null on the other
 * mutation's response and on {@link #of}'s (adjustment). See
 * MULTI_VENDOR_INVENTORY_DESIGN.md section 7.3/8 for what each surfaces:
 * {@code vendorIsNewToProduct} is the "Vendor B is new to this product" confirmation line,
 * {@code cheaperVendorHint} is the "Vendor A is cheaper at this quantity" informational note,
 * and {@code breakdown} is the per-lot receipt shown after a stock-out.
 */
public record StockMutationResponse(
        ProductResponse product,
        StockMovementResponse movement,
        Boolean vendorIsNewToProduct,
        CheaperVendorHint cheaperVendorHint,
        List<AllocationBreakdown> breakdown) {

    /**
     * "Vendor A is X cheaper at this quantity" - the structured facts, not a pre-formatted
     * sentence, so the frontend's receipt step can render its own copy. Mirrors {@code
     * companyvendor.ProductVendorService.CheaperVendorHint} field-for-field; kept as a distinct
     * type here rather than reused directly so this DTO package does not depend on a sibling
     * module's service-internal type.
     */
    public record CheaperVendorHint(UUID companyVendorId, String companyVendorName, java.math.BigDecimal unitPrice, java.math.BigDecimal savingsPerUnit) {
    }

    /**
     * One lot a stock-out drew from, enough to render "12 from Vendor A's Jan 3 delivery".
     *
     * <h2>V20: which of the two dates that sentence means</h2>
     * "Jan 3 delivery" is {@code inMovementOccurredAt} - when the goods arrived - not
     * {@code inMovementCreatedAt}, which is when somebody typed it in. Before V20 those could
     * not differ and this record carried only the second; bulk stock-in makes backdating
     * ordinary, so a receipt rendered from {@code inMovementCreatedAt} would now name today for
     * a delivery that arrived last month. Both are published rather than one replaced: the
     * write-time fact is still the FIFO tiebreak and still what an audit reads, so it has not
     * stopped being worth knowing - it has stopped being the answer to this particular question.
     *
     * <h2>V21: {@code label}, so the receipt is not string-built by the client</h2>
     * "12 kg from <b>3 Jan 2026 · Dangote Nigeria Plc</b>" - the delivery half of that sentence,
     * composed by {@link ProductLotResponse#label}, the same method the lot picker's rows and the
     * stock-out error messages use. Publishing the date and the supplier name separately and
     * leaving a client to join them looks harmless and is not: it is three surfaces each choosing
     * a date format, and it is how a UI with only {@code inMovementId} to hand ends up printing
     * a UUID, which contract non-negotiable 6 forbids. Section 4 puts it plainly - "server-
     * composed, never string-built by the UI".
     *
     * <p>{@code quantity} is in the product's STOCK unit, like every other quantity on this
     * response. A caller rendering it must say so (non-negotiable 2).
     */
    public record AllocationBreakdown(
            UUID inMovementId,
            UUID companyVendorId,
            String companyVendorName,
            int quantity,
            java.time.OffsetDateTime inMovementCreatedAt,
            java.time.OffsetDateTime inMovementOccurredAt,
            String label) {
    }

    public static StockMutationResponse of(Product product, StockMovement movement) {
        return new StockMutationResponse(
                ProductResponse.from(product),
                movement == null ? null : StockMovementResponse.from(movement),
                null,
                null,
                null);
    }

    public static StockMutationResponse ofStockIn(
            Product product, StockMovement movement, boolean vendorIsNewToProduct, CheaperVendorHint cheaperVendorHint) {
        return new StockMutationResponse(
                ProductResponse.from(product),
                StockMovementResponse.from(movement),
                vendorIsNewToProduct,
                cheaperVendorHint,
                null);
    }

    public static StockMutationResponse ofStockOut(
            Product product, StockMovement movement, List<AllocationBreakdown> breakdown) {
        return new StockMutationResponse(
                ProductResponse.from(product), StockMovementResponse.from(movement), null, null, breakdown);
    }
}
