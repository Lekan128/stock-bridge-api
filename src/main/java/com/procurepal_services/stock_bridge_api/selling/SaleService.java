package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.selling.dto.AddSalePriceRequest;
import com.procurepal_services.stock_bridge_api.selling.dto.SaleLineResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SaleResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.UndoSaleResponse;
import com.procurepal_services.stock_bridge_api.settlement.PayoutCadence;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.StockWriteNotUndoableException;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sale, as the person at the counter and the owner see it (SELLING_PRICE_PLAN.md sections 4.3,
 * 4.3a): read it, undo it in the first two minutes, cancel it or one line of it later, or add the
 * price to a line saved without one.
 *
 * <p>Every fix only adds rows - see {@code StockManagementService.cancelSaleLine} and
 * {@code addSalePrice}. Undo is the existing void (D8), applied line by line.
 *
 * <h2>Who may cancel or add a price</h2>
 * The person who recorded the line, the same day (Lagos time), with STOCK_OUT - the "I typed 6, it
 * was 5" fix. Anything else - someone else's sale, or yesterday's - needs MANAGE_INVENTORY. "Sell,
 * keep the cash, cancel it later" is the classic leak, so a cashier cannot reach back, and every
 * cancellation carries who and when.
 *
 * <h2>Repeat-safe by state</h2>
 * Cancelling what is already cancelled, or adding the same price again, answers with the sale as it
 * stands rather than an error - so a cancel retried after a lost reply is harmless, with no key.
 */
@Service
@RequiredArgsConstructor
public class SaleService {

    private final StockMovementRepository stockMovementRepository;
    private final StockManagementService stockManagementService;
    private final StockWritePermissionGuard permissions;

    @Transactional(readOnly = true)
    public SaleResponse get(UUID saleId) {
        return toResponse(saleId, rowsOf(saleId));
    }

    /**
     * Undo sale: voids each of this person's lines that can still be undone, newest first, each in
     * its own transaction (StockManagementService.voidWrite) so one that cannot be undone - two
     * minutes passed, or the product has been sold again since - does not stop the rest.
     */
    public UndoSaleResponse undo(UUID saleId, UUID actingUserId) {
        List<StockMovement> lines = linesOf(rowsOf(saleId)).stream()
                .sorted(Comparator.comparing(StockMovement::getCreatedAt).reversed())
                .toList();
        List<UndoSaleResponse.Line> results = new ArrayList<>();
        for (StockMovement line : lines) {
            String productName = line.getProduct().getName();
            try {
                stockManagementService.voidWrite(line.getProduct().getId(), line.getId(), actingUserId);
                results.add(new UndoSaleResponse.Line(line.getId(), productName, true, null));
            } catch (StockWriteNotUndoableException | AccessDeniedException e) {
                results.add(new UndoSaleResponse.Line(line.getId(), productName, false, e.getMessage()));
            }
        }
        long undone = results.stream().filter(UndoSaleResponse.Line::undone).count();
        return new UndoSaleResponse(saleId, results, (int) undone, results.size() - (int) undone);
    }

    /**
     * Cancels the given lines, or every line not already cancelled. One transaction: either every
     * line asked for is cancelled, or none is and the reason is said.
     */
    @Transactional
    public SaleResponse cancel(UUID saleId, Collection<UUID> lineIds, UUID actingUserId) {
        List<StockMovement> rows = rowsOf(saleId);
        List<StockMovement> lines = linesOf(rows);
        List<StockMovement> wanted = lineIds == null || lineIds.isEmpty()
                ? lines
                : lineIds.stream()
                        .map(id -> lines.stream().filter(line -> line.getId().equals(id)).findFirst()
                                .orElseThrow(SaleNotFoundException::new))
                        .toList();
        Map<UUID, StockMovement> cancellations = fixesBy(rows, StockMovement::getReversesMovementId);
        List<StockMovement> written = new ArrayList<>();
        for (StockMovement line : wanted) {
            if (cancellations.containsKey(line.getId())) {
                continue;
            }
            requireMayFix(line, actingUserId, "cancel");
            written.add(stockManagementService.cancelSaleLine(line, actingUserId));
        }
        List<StockMovement> after = new ArrayList<>(rows);
        after.addAll(written);
        return toResponse(saleId, after);
    }

    /** Adds the price to a sale line saved without one - see {@link AddSalePriceRequest}. */
    @Transactional
    public SaleResponse addPrice(UUID lineId, AddSalePriceRequest request, UUID actingUserId) {
        StockMovement line = stockMovementRepository
                .findByIdAndClientId(lineId, requireTenantId())
                .filter(found -> found.getMovementType() == MovementType.OUT && found.getOutReason() == OutReason.SALE)
                .orElseThrow(SaleNotFoundException::new);
        List<StockMovement> rows = rowsOf(line.getSaleId());
        BigDecimal amount = amountFor(line, request);

        if (fixesBy(rows, StockMovement::getReversesMovementId).containsKey(line.getId())) {
            throw new SaleFixRefusedException("This line was cancelled, so it has no price to add.");
        }
        StockMovement existingFix = fixesBy(rows, StockMovement::getCorrectsMovementId).get(line.getId());
        if (existingFix != null) {
            if (existingFix.getSaleAmount().compareTo(amount) == 0) {
                return toResponse(line.getSaleId(), rows);
            }
            throw new SaleFixRefusedException("A price was already added to this line. Cancel it and record it again to change it.");
        }
        if (line.getSaleAmount() != null) {
            throw new SaleFixRefusedException("This line already has a price. Cancel it and record it again to change it.");
        }
        requireMayFix(line, actingUserId, "add a price to");
        List<StockMovement> after = new ArrayList<>(rows);
        after.add(stockManagementService.addSalePrice(line, amount, actingUserId));
        return toResponse(line.getSaleId(), after);
    }

    // ------------------------------------------------------------------------------ rules ----

    private void requireMayFix(StockMovement line, UUID actingUserId, String verb) {
        if (permissions.has(actingUserId, "MANAGE_INVENTORY")) {
            return;
        }
        boolean own = line.getCreatedBy() != null && line.getCreatedBy().getId().equals(actingUserId);
        boolean today = lagosDay(line.getCreatedAt()).equals(lagosDay(OffsetDateTime.now()));
        if (own && today && permissions.has(actingUserId, "STOCK_OUT")) {
            return;
        }
        throw new AccessDeniedException(own
                ? "Only someone who manages inventory can " + verb + " a sale from an earlier day."
                : "Only someone who manages inventory can " + verb + " a sale someone else recorded.");
    }

    private static LocalDate lagosDay(OffsetDateTime at) {
        return at.atZoneSameInstant(PayoutCadence.ZONE).toLocalDate();
    }

    /**
     * What the line went for: a typed line total exactly, or the price per unit the line was typed
     * in times the quantity as typed - the same rule as recording a sale (SaleFacts.saleAmount).
     */
    private static BigDecimal amountFor(StockMovement line, AddSalePriceRequest request) {
        if (request.lineTotal() != null) {
            return request.lineTotal().setScale(2, RoundingMode.HALF_UP);
        }
        if (request.unitPrice() != null) {
            BigDecimal typedQuantity = line.getEnteredQuantity() != null
                    ? line.getEnteredQuantity()
                    : BigDecimal.valueOf(line.getQuantity());
            return request.unitPrice().multiply(typedQuantity).setScale(2, RoundingMode.HALF_UP);
        }
        throw new InvalidSalePriceException();
    }

    // ------------------------------------------------------------------------------ reading ----

    private List<StockMovement> rowsOf(UUID saleId) {
        List<StockMovement> rows = stockMovementRepository.findAllByClientIdAndSaleIdOrderByCreatedAtAscIdAsc(requireTenantId(), saleId);
        if (linesOf(rows).isEmpty()) {
            throw new SaleNotFoundException();
        }
        return rows;
    }

    private static List<StockMovement> linesOf(List<StockMovement> rows) {
        return rows.stream().filter(row -> row.getMovementType() == MovementType.OUT).toList();
    }

    private static Map<UUID, StockMovement> fixesBy(
            List<StockMovement> rows, java.util.function.Function<StockMovement, UUID> target) {
        Map<UUID, StockMovement> byLine = new HashMap<>();
        for (StockMovement row : rows) {
            UUID lineId = target.apply(row);
            if (lineId != null) {
                byLine.put(lineId, row);
            }
        }
        return byLine;
    }

    private SaleResponse toResponse(UUID saleId, List<StockMovement> rows) {
        List<StockMovement> lines = linesOf(rows);
        Map<UUID, StockMovement> cancellations = fixesBy(rows, StockMovement::getReversesMovementId);
        Map<UUID, StockMovement> priceFixes = fixesBy(rows, StockMovement::getCorrectsMovementId);

        List<SaleLineResponse> lineResponses = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int cancelled = 0;
        int unpriced = 0;
        for (StockMovement line : lines) {
            StockMovement cancellation = cancellations.get(line.getId());
            StockMovement priceFix = priceFixes.get(line.getId());
            BigDecimal amount = line.getSaleAmount() != null
                    ? line.getSaleAmount()
                    : priceFix == null ? null : priceFix.getSaleAmount();
            if (cancellation != null) {
                cancelled++;
            } else if (amount == null) {
                unpriced++;
            } else {
                total = total.add(amount);
            }
            lineResponses.add(new SaleLineResponse(
                    line.getId(),
                    line.getProduct().getId(),
                    line.getProduct().getName(),
                    line.getQuantity(),
                    line.getEnteredUnit(),
                    line.getEnteredQuantity(),
                    line.getUnitPriceAtTime(),
                    line.getEnteredUnitPrice(),
                    amount,
                    line.getCostAmount(),
                    line.getCostBasis(),
                    cancellation != null,
                    cancellation == null ? null : cancellation.getCreatedAt(),
                    cancellation == null ? null : nameOf(cancellation.getCreatedBy()),
                    priceFix != null,
                    line.getOccurredAt(),
                    line.getProduct().getUnitOfMeasure()));
        }
        StockMovement first = lines.get(0);
        return new SaleResponse(
                saleId,
                lines.stream().map(StockMovement::getOccurredAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
                first.getCreatedBy() == null ? null : first.getCreatedBy().getId(),
                nameOf(first.getCreatedBy()),
                lineResponses,
                total,
                lines.size(),
                cancelled,
                unpriced);
    }

    private static String nameOf(User user) {
        if (user == null) {
            return null;
        }
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
