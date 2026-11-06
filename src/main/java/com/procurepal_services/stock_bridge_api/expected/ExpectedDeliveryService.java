package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.branch.BranchStockService;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDelivery;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryLine;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryRequest;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import com.procurepal_services.stock_bridge_api.product.bulk.SheetUnitOptions;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateRow;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateService;
import com.procurepal_services.stock_bridge_api.product.unit.Decimals;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.ExpectedDeliveryRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a company is waiting for from an off-platform supplier (BULK_IMPORT_CX_PLAN.md task 3.1).
 *
 * <p>Two jobs only: answer "what have we got coming?", and hand the delivery screen a filled-in
 * form when it arrives. Receiving itself is not here - it goes through the ordinary stock-in
 * import, and this class is only told afterwards, by {@link #credit} and {@link #uncredit}. One
 * stock path, one ledger.
 */
@Service
@RequiredArgsConstructor
public class ExpectedDeliveryService {

    private static final DateTimeFormatter DUE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final ExpectedDeliveryRepository expectedDeliveryRepository;
    private final CompanyVendorRepository companyVendorRepository;
    private final ProductRepository productRepository;
    private final StockInTemplateService stockInTemplateService;
    /** Which branch it is going to (V52): named, or the company's only one, or the person's only one. */
    private final BranchStockService branchStockService;
    private final com.procurepal_services.stock_bridge_api.branch.BranchAccessService branchAccessService;
    /** Purchase orders: the next PO-number, and the buyer / branch details frozen onto the order. */
    private final PurchaseOrderNumbers purchaseOrderNumbers;
    private final com.procurepal_services.stock_bridge_api.repository.ClientRepository clientRepository;
    private final com.procurepal_services.stock_bridge_api.repository.BranchRepository branchRepository;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;
    private final PurchaseOrderSharing purchaseOrderSharing;
    private final com.procurepal_services.stock_bridge_api.repository.PurchaseOrderEmailRepository purchaseOrderEmailRepository;
    /** The order's history: who made it, and the deliveries received against it. */
    private final com.procurepal_services.stock_bridge_api.repository.UserRepository userRepository;
    private final com.procurepal_services.stock_bridge_api.repository.ImportSessionRepository importSessionRepository;
    /** Approval (plan section 11.5): who may send, who may only ask, and telling them. */
    private final com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard permissions;
    private final com.procurepal_services.stock_bridge_api.notification.NotificationService notificationService;
    private final com.procurepal_services.stock_bridge_api.push.PushService pushService;
    /** What went back at the gate on each delivery received against an order (V79). */
    private final DeliverySendBacks deliverySendBacks;

    static final String CREATE_PURCHASE_ORDER = "CREATE_PURCHASE_ORDER";
    static final String SEND_PURCHASE_ORDER = "SEND_PURCHASE_ORDER";

    private static final java.time.ZoneId LAGOS = java.time.ZoneId.of("Africa/Lagos");

    @Transactional
    public ExpectedDeliveryResponse create(ExpectedDeliveryRequest request, UUID actingUserId) {
        UUID tenantId = requireTenantId();
        if (request.isPurchaseOrder() && request.vendorId() == null) {
            throw ExpectedDeliveryException.needsSupplier();
        }
        // Who may do what (V78): a purchase order needs one of the two order privileges; the old
        // "write down what I ordered" still needs MANAGE_INVENTORY. Someone who can only create
        // makes an order that waits for approval.
        boolean forApproval = false;
        if (actingUserId != null) {
            if (request.isPurchaseOrder()) {
                boolean maySend = permissions.has(actingUserId, SEND_PURCHASE_ORDER);
                if (!maySend && !permissions.has(actingUserId, CREATE_PURCHASE_ORDER)) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Your account can't make purchase orders.");
                }
                forApproval = !maySend;
            } else {
                permissions.require(actingUserId, "MANAGE_INVENTORY");
            }
        }
        // A purchase order asks for something still to come. (An expectation written down after the
        // fact keeps its past dates: a missed promise is exactly what overdue means.)
        if (request.isPurchaseOrder()) {
            LocalDate neededBy = parseDate(request.expectedDate());
            if (neededBy != null && neededBy.isBefore(LocalDate.now(java.time.ZoneId.of("Africa/Lagos")))) {
                throw ExpectedDeliveryException.neededInThePast();
            }
        }
        CompanyVendor vendor = request.vendorId() == null
                ? null
                : companyVendorRepository.findByIdAndClientIdAndActiveTrue(request.vendorId(), tenantId)
                        .orElseThrow(ExpectedDeliveryException::noSuchSupplier);

        // The ways each of these products is actually bought, read through the tenant's own
        // catalog. This is the same call the delivery picker makes, so an expectation can only
        // ever be recorded in a unit a delivery could later be received in.
        Map<UUID, Map<String, StockInTemplateRow>> waysToBuy = waysToBuy(
                request.lines().stream().map(ExpectedDeliveryRequest.Line::productId).distinct().toList());

        ExpectedDelivery expected = ExpectedDelivery.builder()
                .vendor(vendor)
                .expectedDate(parseDate(request.expectedDate()))
                .reference(blankToNull(request.reference()))
                .note(blankToNull(request.note()))
                .status(ExpectedDeliveryStatus.OPEN)
                .createdBy(actingUserId)
                .branchId(goingTo(request.branchId(), actingUserId))
                .build();

        for (ExpectedDeliveryRequest.Line line : request.lines()) {
            Map<String, StockInTemplateRow> byUnit = waysToBuy.get(line.productId());
            if (byUnit == null || byUnit.isEmpty()) {
                throw ExpectedDeliveryException.noSuchProduct();
            }
            StockInTemplateRow row = byUnit.get(line.unit());
            if (row == null) {
                throw ExpectedDeliveryException.unknownUnit(
                        byUnit.values().iterator().next().productName(),
                        UnitOptions.spokenPhraseOfSubmitted(line.unit()));
            }
            Product product = productRepository.getReferenceById(line.productId());
            expected.addLine(ExpectedDeliveryLine.builder()
                    .product(product)
                    .unit(line.unit())
                    .quantity(line.quantity())
                    // Per unit as ordered (plan section 11.4). Since 2026-11-06 a purchase order
                    // carries its prices too.
                    .price(line.price())
                    .receivedQuantity(BigDecimal.ZERO)
                    .build());
        }

        if (request.isPurchaseOrder()) {
            expected.setDeliveryCharge(request.deliveryCharge());
            makePurchaseOrder(expected, tenantId, waysToBuy);
            if (forApproval) {
                // Nothing goes to the supplier - not an email, not "ordered by phone" - until approved.
                askForApproval(expected, actingUserId, request.approvalNote());
            } else if (request.isOrderedByPhone() && !request.isEmailRequested()) {
                // Ordered by phone already: the supplier has it, so it is not waiting to be sent.
                expected.markSent("PHONE", java.time.OffsetDateTime.now());
            }
        }
        ExpectedDelivery saved = expectedDeliveryRepository.saveAndFlush(expected);
        if (forApproval) {
            tellApprovers(saved, actingUserId);
        }
        return respond(saved);
    }

    /**
     * Change a purchase order (plan 4.4): its lines, needed-by date and note. A sent order is never
     * edited silently - the change becomes the next revision, the PDF says "Revised (2)", and the
     * order is back on To send until the supplier is given it. Nothing can go below what has already
     * arrived, and a line that has had something delivered stays on.
     */
    @Transactional
    public ExpectedDeliveryResponse change(UUID id, ExpectedDeliveryRequest request, UUID userId) {
        UUID tenantId = requireTenantId();
        ExpectedDelivery expected = require(id);
        if (userId != null && branchStockService.isMultiBranch(tenantId)) {
            branchAccessService.requireMayRecordAt(userId, expected.getBranchId());
        }
        if (!expected.isPurchaseOrder()) {
            throw ExpectedDeliveryException.notAPurchaseOrder();
        }
        if (expected.getStatus() != ExpectedDeliveryStatus.OPEN) {
            throw ExpectedDeliveryException.alreadyClosed(
                    expected.getStatus() == ExpectedDeliveryStatus.RECEIVED ? "received" : "called off");
        }
        // A change by someone who can only create goes (back) for approval, whatever it was before.
        boolean forApproval = false;
        if (userId != null) {
            boolean maySend = permissions.has(userId, SEND_PURCHASE_ORDER);
            if (!maySend && !permissions.has(userId, CREATE_PURCHASE_ORDER)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Your account can't change purchase orders.");
            }
            forApproval = !maySend;
        }
        LocalDate neededBy = parseDate(request.expectedDate());
        if (neededBy != null && neededBy.isBefore(LocalDate.now(LAGOS))
                && !neededBy.equals(expected.getExpectedDate())) {
            throw ExpectedDeliveryException.neededInThePast();
        }

        Map<UUID, Map<String, StockInTemplateRow>> waysToBuy = waysToBuy(java.util.stream.Stream.concat(
                        request.lines().stream().map(ExpectedDeliveryRequest.Line::productId),
                        expected.getLines().stream().map(line -> line.getProduct().getId()))
                .distinct().toList());

        Map<ReceiptKey, ExpectedDeliveryLine> existing = new LinkedHashMap<>();
        for (ExpectedDeliveryLine line : expected.getLines()) {
            existing.put(new ReceiptKey(line.getProduct().getId(), line.getUnit()), line);
        }
        java.util.Set<ReceiptKey> kept = new java.util.HashSet<>();
        for (ExpectedDeliveryRequest.Line line : request.lines()) {
            Map<String, StockInTemplateRow> byUnit = waysToBuy.get(line.productId());
            if (byUnit == null || byUnit.isEmpty()) {
                throw ExpectedDeliveryException.noSuchProduct();
            }
            StockInTemplateRow row = byUnit.get(line.unit());
            if (row == null) {
                throw ExpectedDeliveryException.unknownUnit(
                        byUnit.values().iterator().next().productName(),
                        UnitOptions.spokenPhraseOfSubmitted(line.unit()));
            }
            ReceiptKey key = new ReceiptKey(line.productId(), line.unit());
            kept.add(key);
            ExpectedDeliveryLine current = existing.get(key);
            if (current == null) {
                expected.addLine(ExpectedDeliveryLine.builder()
                        .product(productRepository.getReferenceById(line.productId()))
                        .unit(line.unit())
                        .quantity(line.quantity())
                        .price(line.price())
                        .receivedQuantity(BigDecimal.ZERO)
                        .build());
            } else {
                if (line.quantity().compareTo(current.getReceivedQuantity()) < 0) {
                    throw ExpectedDeliveryException.belowReceived(row.productName(), current.getReceivedQuantity());
                }
                current.setQuantity(line.quantity());
                current.setPrice(line.price());
            }
        }
        for (Map.Entry<ReceiptKey, ExpectedDeliveryLine> entry : existing.entrySet()) {
            if (kept.contains(entry.getKey())) {
                continue;
            }
            if (entry.getValue().getReceivedQuantity().signum() > 0) {
                throw ExpectedDeliveryException.cannotRemoveReceived(entry.getValue().getProduct().getName());
            }
            expected.getLines().remove(entry.getValue());
        }

        expected.setExpectedDate(neededBy);
        expected.setNote(blankToNull(request.note()));
        expected.setDeliveryCharge(request.deliveryCharge());
        // A change nobody has been told about yet is still the first order; once the supplier has
        // it, the change is the next revision.
        if (expected.getSentRevision() != null && expected.getSentRevision() >= expected.getRevision()) {
            expected.setRevision(expected.getRevision() + 1);
        }
        expected.setSnapshot(writeSnapshot(buildSnapshot(expected, tenantId, waysToBuy)));
        expected.setStatus(expected.isFullyReceived() ? ExpectedDeliveryStatus.RECEIVED : ExpectedDeliveryStatus.OPEN);
        if (forApproval) {
            askForApproval(expected, userId, request.approvalNote());
        }
        ExpectedDelivery saved = expectedDeliveryRepository.saveAndFlush(expected);
        if (forApproval) {
            tellApprovers(saved, userId);
        }
        return respond(saved);
    }

    // ------------------------------------------------------------------------------ approval ----

    private static void askForApproval(ExpectedDelivery expected, UUID userId, String note) {
        expected.setApproval(ExpectedDelivery.APPROVAL_WAITING);
        expected.setApprovalAskedBy(userId);
        expected.setApprovalAskedAt(java.time.OffsetDateTime.now());
        expected.setApprovalNote(blankToNull(note));
    }

    /**
     * "Approve and send" (plan section 11.5): the order is approved and from here is sent like any
     * other. Whoever sent it for approval is told.
     */
    @Transactional
    public ExpectedDeliveryResponse approve(UUID id, UUID userId) {
        ExpectedDelivery expected = requireOpenOrder(id, userId);
        permissions.require(userId, SEND_PURCHASE_ORDER);
        if (!expected.isAwaitingApproval()) {
            throw ExpectedDeliveryException.notWaitingForApproval();
        }
        expected.setApproval(ExpectedDelivery.APPROVAL_APPROVED);
        expected.setApprovedBy(userId);
        expected.setApprovedAt(java.time.OffsetDateTime.now());
        ExpectedDelivery saved = expectedDeliveryRepository.saveAndFlush(expected);
        tellAsker(saved, userId, saved.getPoNumber() + " is approved",
                nameOf(userId) + " approved it and will send it to " + supplierName(saved) + ".");
        return respond(saved);
    }

    /** "Ask for a change": back to whoever made it, with what to change. */
    @Transactional
    public ExpectedDeliveryResponse askForChange(UUID id, UUID userId, String note) {
        ExpectedDelivery expected = requireOpenOrder(id, userId);
        permissions.require(userId, SEND_PURCHASE_ORDER);
        if (!ExpectedDelivery.APPROVAL_WAITING.equals(expected.getApproval())) {
            throw ExpectedDeliveryException.notWaitingForApproval();
        }
        String said = blankToNull(note);
        if (said == null) {
            throw ExpectedDeliveryException.sayWhatToChange();
        }
        expected.setApproval(ExpectedDelivery.APPROVAL_CHANGE_ASKED);
        expected.setChangeAskedBy(userId);
        expected.setChangeAskedAt(java.time.OffsetDateTime.now());
        expected.setChangeNote(said.length() > 500 ? said.substring(0, 500) : said);
        ExpectedDelivery saved = expectedDeliveryRepository.saveAndFlush(expected);
        tellAsker(saved, userId, nameOf(userId) + " asked for a change to " + saved.getPoNumber(), "\u201c" + said + "\u201d");
        return respond(saved);
    }

    /** Refuses to send an order that is held for approval, by any way: email, WhatsApp, copied, by phone. */
    public void requireMaySend(UUID id) {
        if (require(id).isAwaitingApproval()) {
            throw ExpectedDeliveryException.waitingForApproval();
        }
    }

    private ExpectedDelivery requireOpenOrder(UUID id, UUID userId) {
        ExpectedDelivery expected = require(id);
        if (userId != null && branchStockService.isMultiBranch(requireTenantId())) {
            branchAccessService.requireMayRecordAt(userId, expected.getBranchId());
        }
        if (!expected.isPurchaseOrder()) {
            throw ExpectedDeliveryException.notAPurchaseOrder();
        }
        if (expected.getStatus() != ExpectedDeliveryStatus.OPEN) {
            throw ExpectedDeliveryException.alreadyClosed(
                    expected.getStatus() == ExpectedDeliveryStatus.RECEIVED ? "received" : "called off");
        }
        return expected;
    }

    /**
     * To everyone who can approve at that branch, but not the person asking: the bell, and the phone
     * even with the app closed. No email (owner's email rules: the chip's count is the reminder).
     */
    private void tellApprovers(ExpectedDelivery expected, UUID askedBy) {
        UUID tenantId = requireTenantId();
        boolean multiBranch = branchStockService.isMultiBranch(tenantId);
        List<UUID> approvers = userRepository.activeIdsWithPermission(tenantId, SEND_PURCHASE_ORDER).stream()
                .filter(person -> !person.equals(askedBy))
                .filter(person -> !multiBranch || branchAccessService.mayRecordAt(person, expected.getBranchId()))
                .toList();
        if (approvers.isEmpty()) {
            return;
        }
        String title = expected.getPoNumber() + " needs your approval";
        BigDecimal total = orderTotal(expected);
        String body = nameOf(askedBy) + " wants to send it to " + supplierName(expected)
                + (total == null ? "" : " · " + PurchaseOrderSnapshot.naira(total));
        String link = "/app/purchase-orders/" + expected.getId();
        for (UUID person : approvers) {
            notificationService.notifyClient(tenantId, person,
                    com.procurepal_services.stock_bridge_api.entity.NotificationType.PO_APPROVAL_WAITING, title, body, link, null);
        }
        pushService.notify(approvers, new com.procurepal_services.stock_bridge_api.push.PushMessage(
                title, body, "po-approval-" + expected.getId(), link, false));
    }

    private void tellAsker(ExpectedDelivery expected, UUID doneBy, String title, String body) {
        UUID asker = expected.getApprovalAskedBy();
        if (asker == null || asker.equals(doneBy)) {
            return;
        }
        String link = "/app/purchase-orders/" + expected.getId();
        notificationService.notifyClient(requireTenantId(), asker,
                com.procurepal_services.stock_bridge_api.entity.NotificationType.PO_APPROVAL_ANSWERED, title, body, link, null);
        pushService.notify(List.of(asker), new com.procurepal_services.stock_bridge_api.push.PushMessage(
                title, body, "po-approval-" + expected.getId(), link, false));
    }

    private String nameOf(UUID userId) {
        if (userId == null) {
            return "Someone";
        }
        return userRepository.findById(userId).map(ExpectedDeliveryService::nameOf).orElse("Someone");
    }

    private static String supplierName(ExpectedDelivery expected) {
        return expected.getVendor() == null ? "the supplier" : expected.getVendor().getName();
    }

    /** The lines plus delivery when every line is priced; null otherwise. */
    private static BigDecimal orderTotal(ExpectedDelivery expected) {
        BigDecimal total = BigDecimal.ZERO;
        for (ExpectedDeliveryLine line : expected.getLines()) {
            if (line.getPrice() == null) {
                return null;
            }
            total = total.add(line.getPrice().multiply(line.getQuantity()));
        }
        return expected.getDeliveryCharge() == null ? total : total.add(expected.getDeliveryCharge());
    }

    /** The supplier now has the current revision (it was emailed, or shared by hand). */
    @Transactional
    public void recordSent(UUID id, String via) {
        expectedDeliveryRepository.findByIdAndClientId(id, requireTenantId()).ifPresent(expected -> {
            if (expected.isPurchaseOrder()) {
                expected.markSent(via, java.time.OffsetDateTime.now());
                expectedDeliveryRepository.saveAndFlush(expected);
            }
        });
    }

    /**
     * Turns the expectation into a purchase order: takes the next number for this company and
     * freezes what the supplier will be shown. Runs in the transaction that saves the row, so the
     * number and the order stand or fall together.
     */
    private void makePurchaseOrder(
            ExpectedDelivery expected, UUID tenantId, Map<UUID, Map<String, StockInTemplateRow>> waysToBuy) {
        int sequence = purchaseOrderNumbers.nextSequence(tenantId);
        expected.setPoSequence(sequence);
        expected.setPoNumber(PurchaseOrderNumbers.format(sequence));
        expected.setShareToken(PurchaseOrderSharing.newToken());
        expected.setSnapshot(writeSnapshot(buildSnapshot(expected, tenantId, waysToBuy)));
    }

    private PurchaseOrderSnapshot buildSnapshot(
            ExpectedDelivery expected, UUID tenantId, Map<UUID, Map<String, StockInTemplateRow>> waysToBuy) {
        var company = clientRepository.findById(tenantId).orElse(null);
        PurchaseOrderSnapshot.Party buyer = new PurchaseOrderSnapshot.Party(
                company == null ? null : company.getName(),
                company == null ? null : blankToNull(company.getPhone()),
                company == null ? null : blankToNull(company.getAdminContactEmail()),
                company == null ? null : joinAddress(
                        company.getAddressLine1(), company.getAddressLine2(), company.getCity(), company.getState()),
                company == null ? null : blankToNull(company.getLogoUrl()));

        CompanyVendor vendor = expected.getVendor();
        // A supplier that is on the platform may leave its own phone / email blank in the buyer's
        // directory; the platform account's details are the better answer than nothing.
        var platform = vendor.getPlatformClientId() == null
                ? null
                : clientRepository.findById(vendor.getPlatformClientId()).orElse(null);
        PurchaseOrderSnapshot.Party supplier = new PurchaseOrderSnapshot.Party(
                vendor.getName(),
                firstNonBlank(vendor.getContactPhone(), platform == null ? null : platform.getPhone()),
                firstNonBlank(vendor.getEmail(), platform == null ? null : platform.getAdminContactEmail()),
                joinAddress(vendor.getAddressLine1(), vendor.getAddressLine2(), vendor.getCity(), vendor.getState()),
                null);

        var branch = branchRepository.findById(expected.getBranchId())
                .filter(found -> tenantId.equals(found.getClientId()))
                .orElse(null);
        PurchaseOrderSnapshot.Delivery deliverTo = branch == null
                ? null
                : new PurchaseOrderSnapshot.Delivery(
                        branch.getName(), blankToNull(branch.getAddress()), blankToNull(branch.getPhone()));

        List<PurchaseOrderSnapshot.Line> lines = new ArrayList<>();
        for (ExpectedDeliveryLine line : expected.getLines()) {
            StockInTemplateRow row = Optional.ofNullable(waysToBuy.get(line.getProduct().getId()))
                    .map(byUnit -> byUnit.get(line.getUnit()))
                    .orElse(null);
            lines.add(new PurchaseOrderSnapshot.Line(
                    row != null ? row.productName() : line.getProduct().getName(),
                    row != null ? row.sku() : line.getProduct().getSku(),
                    row != null
                            ? SheetUnitOptions.comesInLabel(row.option())
                            : UnitOptions.spokenPhraseOfSubmitted(line.getUnit()),
                    line.getQuantity(),
                    line.getPrice()));
        }
        return new PurchaseOrderSnapshot(
                expected.getPoNumber(), expected.getRevision(), LocalDate.now(java.time.ZoneId.of("Africa/Lagos")),
                buyer, supplier, deliverTo,
                expected.getExpectedDate(), expected.getNote(), lines, expected.getDeliveryCharge());
    }

    private String writeSnapshot(PurchaseOrderSnapshot snapshot) {
        return jsonMapper.writeValueAsString(snapshot);
    }

    /** "12 Marina Rd, Lekki, Lagos" - the parts that are there, in order, comma-separated. */
    private static String joinAddress(String... parts) {
        String joined = java.util.Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }

    private static String firstNonBlank(String first, String second) {
        return blankToNull(first) != null ? first.trim() : blankToNull(second);
    }

    /**
     * Where it is going: named, or the only branch. A person files it only at a branch they may record at
     * - it lands on that branch's Needs you, and receiving it there is theirs to do.
     */
    private UUID goingTo(UUID requested, UUID actingUserId) {
        UUID branchId = branchStockService.resolveForWrite(requested, actingUserId);
        if (actingUserId != null && branchStockService.isMultiBranch(requireTenantId())) {
            branchAccessService.requireMayRecordAt(actingUserId, branchId);
        }
        return branchId;
    }

    @Transactional(readOnly = true)
    public Page<ExpectedDeliveryResponse> list(ExpectedDeliveryStatus status, Pageable pageable) {
        return list(status, pageable, null, null);
    }

    /**
     * At a multi-branch company (MULTI_BRANCH_UX_PLAN.md section 5.1): at a branch, the deliveries going
     * there; in All branches, every one going to a branch the person sees.
     */
    @Transactional(readOnly = true)
    public Page<ExpectedDeliveryResponse> list(ExpectedDeliveryStatus status, Pageable pageable, UUID userId, UUID branchId) {
        UUID tenantId = requireTenantId();
        List<UUID> scope = userId != null && branchStockService.isMultiBranch(tenantId)
                ? branchAccessService.reportScope(userId, branchId)
                : null;
        Page<ExpectedDelivery> page = scope != null
                ? status == null
                        ? expectedDeliveryRepository.findAllByClientIdAndBranchIdInOrderByCreatedAtDesc(tenantId, scope, pageable)
                        : expectedDeliveryRepository.findAllByClientIdAndStatusAndBranchIdInOrderByCreatedAtDesc(
                                tenantId, status, scope, pageable)
                : status == null
                ? expectedDeliveryRepository.findAllByClientIdOrderByCreatedAtDesc(tenantId, pageable)
                : expectedDeliveryRepository.findAllByClientIdAndStatusOrderByCreatedAtDesc(tenantId, status, pageable);
        // The list gets the token and when it was shared by hand, but not the message: composing that
        // needs the order's frozen copy, and a list of twenty does not need twenty of them.
        return respondToPage(page);
    }

    /** The list's filter chips (redesign 2026-11-04). */
    public enum View { ALL, OPEN, TO_APPROVE, TO_SEND, WAITING, LATE, ARRIVED, CALLED_OFF }

    /** What each chip would show, for the counts beside them (Pattern B: a zero you can read). */
    public record Counts(long toSend, long waiting, long late, long arrived, long all, long toApprove) {
    }

    /** The Purchase orders list: searched, filtered by chip and supplier, scoped to the person's branches. */
    @Transactional(readOnly = true)
    public Page<ExpectedDeliveryResponse> search(
            View view, String search, UUID vendorId, Pageable pageable, UUID userId, UUID branchId) {
        UUID tenantId = requireTenantId();
        List<UUID> scope = scope(tenantId, userId, branchId);
        Page<ExpectedDelivery> page = expectedDeliveryRepository.search(
                tenantId, scope == null, scope == null ? List.of(NOWHERE) : orNowhere(scope),
                vendorId == null, vendorId == null ? NOWHERE : vendorId,
                likePattern(search), (view == null ? View.ALL : view).name(), LocalDate.now(LAGOS), pageable);
        return respondToPage(page);
    }

    @Transactional(readOnly = true)
    public Counts counts(String search, UUID vendorId, UUID userId, UUID branchId) {
        UUID tenantId = requireTenantId();
        List<UUID> scope = scope(tenantId, userId, branchId);
        List<Object[]> rows = expectedDeliveryRepository.counts(
                tenantId, scope == null, scope == null ? List.of(NOWHERE) : orNowhere(scope),
                vendorId == null, vendorId == null ? NOWHERE : vendorId,
                likePattern(search), LocalDate.now(LAGOS));
        Object[] row = rows.isEmpty() ? new Object[6] : rows.getFirst();
        return new Counts(asLong(row[0]), asLong(row[1]), asLong(row[2]), asLong(row[3]), asLong(row[4]), asLong(row[5]));
    }

    /** A UUID no branch or supplier has: what an unused "in" list or comparison is given instead of null. */
    private static final UUID NOWHERE = new UUID(0, 0);

    private static List<UUID> orNowhere(List<UUID> ids) {
        return ids.isEmpty() ? List.of(NOWHERE) : ids;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** The branches a person sees at a multi-branch company; null where branches don't come into it. */
    private List<UUID> scope(UUID tenantId, UUID userId, UUID branchId) {
        return userId != null && branchStockService.isMultiBranch(tenantId)
                ? branchAccessService.reportScope(userId, branchId)
                : null;
    }

    /** "%rice%", lowercased, LIKE's own characters made literal (escape '!'); "%" for no search. */
    static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return "%";
        }
        String escaped = search.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private Page<ExpectedDeliveryResponse> respondToPage(Page<ExpectedDelivery> page) {
        Map<UUID, Map<String, StockInTemplateRow>> waysToBuy = waysToBuy(page.getContent().stream()
                .flatMap(expected -> expected.getLines().stream())
                .map(line -> line.getProduct().getId())
                .distinct()
                .toList());
        Map<UUID, com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmail> latestEmails =
                latestEmails(page.getContent().stream().map(ExpectedDelivery::getId).toList());
        Map<UUID, String> names = namesOf(page.getContent().stream()
                .flatMap(expected -> java.util.stream.Stream.of(expected.getCreatedBy(), expected.getApprovalAskedBy()))
                .filter(java.util.Objects::nonNull).distinct().toList());
        return page.map(expected -> respond(expected, waysToBuy, latestEmails, liteShareOf(expected), names, null));
    }

    @Transactional(readOnly = true)
    public ExpectedDeliveryResponse get(UUID id) {
        return get(id, null);
    }

    /** One going to a branch this person sees (D-U3); another branch's is not found, as in the list. */
    @Transactional(readOnly = true)
    public ExpectedDeliveryResponse get(UUID id, UUID userId) {
        ExpectedDelivery expected = require(id);
        if (userId != null && branchStockService.isMultiBranch(requireTenantId())
                && !branchAccessService.viewBranchIds(userId).contains(expected.getBranchId())) {
            throw ExpectedDeliveryException.notFound();
        }
        return respond(expected);
    }

    /**
     * What the supplier was shown on this purchase order, for the PDF. Visible to the same people
     * who can see the order itself; an expectation that was only written down has none.
     */
    @Transactional(readOnly = true)
    public PurchaseOrderSnapshot snapshotOf(UUID id, UUID userId) {
        get(id, userId);
        ExpectedDelivery expected = require(id);
        if (expected.getSnapshot() == null) {
            throw ExpectedDeliveryException.notAPurchaseOrder();
        }
        return jsonMapper.readValue(expected.getSnapshot(), PurchaseOrderSnapshot.class);
    }

    /** What emailing a purchase order needs to know, read in one transaction. */
    public record PurchaseOrderTarget(
            UUID orderId, UUID vendorId, int revision, boolean calledOff, PurchaseOrderSnapshot snapshot) {
    }

    @Transactional(readOnly = true)
    public PurchaseOrderTarget purchaseOrderTarget(UUID id, UUID userId) {
        PurchaseOrderSnapshot snapshot = snapshotOf(id, userId);
        ExpectedDelivery expected = require(id);
        return new PurchaseOrderTarget(
                expected.getId(),
                expected.getVendor() == null ? null : expected.getVendor().getId(),
                expected.getRevision(),
                expected.getStatus() == ExpectedDeliveryStatus.CANCELLED,
                snapshot);
    }

    public ExpectedDeliveryResponse cancel(UUID id) {
        return cancel(id, null);
    }

    /** Called off by somebody who records at the branch it is going to - it is on that branch's Needs you. */
    @Transactional
    public ExpectedDeliveryResponse cancel(UUID id, UUID userId) {
        ExpectedDelivery expected = require(id);
        if (userId != null && branchStockService.isMultiBranch(requireTenantId())) {
            branchAccessService.requireMayRecordAt(userId, expected.getBranchId());
        }
        if (expected.getStatus() == ExpectedDeliveryStatus.RECEIVED) {
            throw ExpectedDeliveryException.alreadyClosed("received");
        }
        // Calling off a purchase order is for whoever can send - or the one who made it, while it
        // has not been approved yet (their own request, taken back). The old kind needs MANAGE_INVENTORY.
        if (userId != null) {
            if (!expected.isPurchaseOrder()) {
                permissions.require(userId, "MANAGE_INVENTORY");
            } else if (!permissions.has(userId, SEND_PURCHASE_ORDER)
                    && !(expected.isAwaitingApproval() && userId.equals(expected.getCreatedBy()))) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Only someone who can send purchase orders can call this one off.");
            }
        }
        expected.setStatus(ExpectedDeliveryStatus.CANCELLED);
        return respond(expectedDeliveryRepository.saveAndFlush(expected));
    }

    /**
     * How much of each product is still expected, <b>in that product's own stock unit</b>, for the
     * product list's "and N coming".
     *
     * <p>Kept separate from {@code Product.incoming_quantity} deliberately - see
     * {@link ExpectedDelivery}'s own note on why a promise and a paid marketplace order must not be
     * added together.
     *
     * <h2>Why this converts instead of summing</h2>
     * Each line is counted in the unit it was ordered in. Ten 50 kg bags and five loose kg are
     * "10" and "5", and 15 is not a quantity of anything - least of all one to print beside
     * "1,000 kg on hand". So every line is taken to the product's stock unit first, which is the
     * only basis on which two of them can be added, and is the number the figure beside it is in.
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> outstandingByProduct(UUID tenantId, List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = expectedDeliveryRepository.outstandingByProductAndUnit(tenantId, productIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Product> products = new HashMap<>();
        for (Product product : productRepository.findAllById(
                rows.stream().map(row -> (UUID) row[0]).distinct().toList())) {
            products.put(product.getId(), product);
        }

        Map<UUID, BigDecimal> outstanding = new HashMap<>();
        for (Object[] row : rows) {
            UUID productId = (UUID) row[0];
            String unit = (String) row[1];
            BigDecimal quantity = (BigDecimal) row[2];
            Product product = products.get(productId);
            if (product == null || quantity == null) {
                continue;
            }
            BigDecimal inStockUnits = UnitOptions.resolveKey(UnitOptions.forProduct(product), unit)
                    .map(option -> quantity.multiply(option.factorToStockUnit()))
                    // The pack has been changed or removed since the order was placed. The number
                    // is then in a unit nothing can convert, so it is left as counted rather than
                    // silently multiplied by a factor that no longer applies.
                    .orElse(quantity);
            outstanding.merge(productId, inStockUnits, BigDecimal::add);
        }
        // Never "5E+2" for 500 - see Decimals for why that is the trap it is.
        outstanding.replaceAll((productId, total) -> Decimals.plain(total));
        return outstanding;
    }

    /**
     * Credits what a committed stock-in actually received, line by line, and closes the record once
     * every line is met. Matched on {product, unit} - the same pair the expectation was written in
     * and the delivery screen sent back - so a receipt of something that was not expected simply
     * finds no line and is left alone rather than being forced onto the nearest one.
     *
     * @param received how much arrived, keyed by {productId, unit}, counted in that unit.
     */
    @Transactional
    public void credit(UUID expectedDeliveryId, UUID tenantId, Map<ReceiptKey, BigDecimal> received) {
        apply(expectedDeliveryId, tenantId, received, false);
    }

    /** The same, reversed: an undone import un-receives exactly what it credited. */
    @Transactional
    public void uncredit(UUID expectedDeliveryId, UUID tenantId, Map<ReceiptKey, BigDecimal> received) {
        apply(expectedDeliveryId, tenantId, received, true);
    }

    private void apply(
            UUID expectedDeliveryId, UUID tenantId, Map<ReceiptKey, BigDecimal> received, boolean reverse) {
        if (expectedDeliveryId == null || received == null || received.isEmpty()) {
            return;
        }
        Optional<ExpectedDelivery> found =
                expectedDeliveryRepository.findByIdAndClientId(expectedDeliveryId, tenantId);
        if (found.isEmpty()) {
            return;
        }
        ExpectedDelivery expected = found.get();
        if (expected.getStatus() == ExpectedDeliveryStatus.CANCELLED) {
            // Cancelled after the goods were already on their way. The stock is real and stays;
            // there is simply no expectation left to credit.
            return;
        }
        for (ExpectedDeliveryLine line : expected.getLines()) {
            BigDecimal amount = received.get(new ReceiptKey(line.getProduct().getId(), line.getUnit()));
            if (amount != null) {
                line.credit(reverse ? amount.negate() : amount);
            }
        }
        // Goods turned up: whatever the supplier was or wasn't sent, they have this order, so it is
        // no longer waiting on "To send" (redesign 2026-11-05). How it reached them stays as recorded.
        if (!reverse && expected.isWaitingToBeSent()) {
            expected.setSentRevision(expected.getRevision());
        }
        expected.setStatus(
                expected.isFullyReceived() ? ExpectedDeliveryStatus.RECEIVED : ExpectedDeliveryStatus.OPEN);
        expectedDeliveryRepository.saveAndFlush(expected);
    }

    /** What a receipt is matched on: the product, and the unit it was counted in. */
    public record ReceiptKey(UUID productId, String unit) {
    }

    /** The company's expectation with this id, for the receive flow as well as the screens. */
    @Transactional(readOnly = true)
    public ExpectedDelivery require(UUID id) {
        return expectedDeliveryRepository.findByIdAndClientId(id, requireTenantId())
                .orElseThrow(ExpectedDeliveryException::notFound);
    }

    // ------------------------------------------------------------------------------ responses ----

    private ExpectedDeliveryResponse respond(ExpectedDelivery expected) {
        UUID tenantId = requireTenantId();
        // The order's history: each delivery received against it, and who received it.
        List<com.procurepal_services.stock_bridge_api.entity.ImportSession> deliveries =
                importSessionRepository.findAllByClientIdAndExpectedDeliveryIdAndStatusOrderByCommittedAtAsc(
                        tenantId, expected.getId(), com.procurepal_services.stock_bridge_api.entity.ImportStatus.COMMITTED);
        List<UUID> people = new ArrayList<>();
        for (UUID person : new UUID[] {expected.getCreatedBy(), expected.getApprovalAskedBy(),
                expected.getApprovedBy(), expected.getChangeAskedBy()}) {
            if (person != null && !people.contains(person)) {
                people.add(person);
            }
        }
        Map<UUID, String> names = namesOf(people);
        Map<UUID, Map<String, StockInTemplateRow>> ways = waysToBuy(expected.getLines().stream()
                .map(line -> line.getProduct().getId())
                .distinct()
                .toList());
        Map<UUID, List<DeliverySendBacks.Recorded>> sentBack = deliverySendBacks.ofCommitted(
                tenantId, deliveries.stream().map(com.procurepal_services.stock_bridge_api.entity.ImportSession::getId).toList());
        List<ExpectedDeliveryResponse.Receipt> receipts = deliveries.stream()
                .map(session -> new ExpectedDeliveryResponse.Receipt(
                        session.getId(),
                        session.getCommittedAt() != null ? session.getCommittedAt() : session.getUpdatedAt(),
                        session.getUploadedBy() == null ? null : nameOf(session.getUploadedBy()),
                        session.getValidCount(),
                        sentBack.getOrDefault(session.getId(), List.of()).stream()
                                .map(back -> new ExpectedDeliveryResponse.SentBack(
                                        back.productId(), back.productName(), back.unit(),
                                        Optional.ofNullable(ways.get(back.productId()))
                                                .map(byUnit -> byUnit.get(back.unit()))
                                                .map(row -> SheetUnitOptions.comesInLabel(row.option()))
                                                .orElse(UnitOptions.spokenPhraseOfSubmitted(back.unit())),
                                        back.quantity(), back.reason(), back.note()))
                                .toList()))
                .toList();
        return respond(expected, ways, latestEmails(List.of(expected.getId())), shareOf(expected), names, receipts);
    }

    /** "Dami Ade", or the username when no name was given. */
    private static String nameOf(com.procurepal_services.stock_bridge_api.entity.User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private Map<UUID, String> namesOf(List<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (var user : userRepository.findAllById(userIds)) {
                names.put(user.getId(), nameOf(user));
            }
        }
        return names;
    }

    private static ExpectedDeliveryResponse.Share liteShareOf(ExpectedDelivery expected) {
        return expected.getShareToken() == null
                ? null
                : new ExpectedDeliveryResponse.Share(
                        expected.getShareToken(), null, null, null,
                        expected.getWhatsappOpenedAt(), expected.getMessageCopiedAt());
    }

    /** The link, message and WhatsApp address for a single purchase order; null for any other. */
    private ExpectedDeliveryResponse.Share shareOf(ExpectedDelivery expected) {
        if (expected.getSnapshot() == null || expected.getShareToken() == null) {
            return null;
        }
        PurchaseOrderSnapshot snapshot = jsonMapper.readValue(expected.getSnapshot(), PurchaseOrderSnapshot.class);
        String livePhone = expected.getVendor() == null ? null : expected.getVendor().getContactPhone();
        return purchaseOrderSharing.build(expected, snapshot, livePhone);
    }

    /** Which way the buyer sent it themselves. We record that they did, never that it arrived. */
    public enum ShareChannel { WHATSAPP, MESSAGE }

    @Transactional
    public ExpectedDeliveryResponse markShared(UUID id, UUID userId, ShareChannel channel) {
        get(id, userId);
        ExpectedDelivery expected = require(id);
        if (expected.getShareToken() == null) {
            throw ExpectedDeliveryException.notAPurchaseOrder();
        }
        if (expected.isAwaitingApproval()) {
            throw ExpectedDeliveryException.waitingForApproval();
        }
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        if (channel == ShareChannel.WHATSAPP) {
            expected.setWhatsappOpenedAt(now);
        } else {
            expected.setMessageCopiedAt(now);
        }
        // Opening WhatsApp or copying the message is the buyer sending it themselves: the order
        // leaves To send. (It says "You opened WhatsApp" - never that it was delivered.)
        if (expected.getStatus() == ExpectedDeliveryStatus.OPEN) {
            expected.markSent(channel.name(), now);
        }
        return respond(expectedDeliveryRepository.saveAndFlush(expected));
    }

    /** The newest attempt to email each of these orders, in one query. */
    private Map<UUID, com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmail> latestEmails(List<UUID> ids) {
        Map<UUID, com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmail> latest = new HashMap<>();
        if (ids.isEmpty()) {
            return latest;
        }
        // Newest first, so the first one seen per order is the latest.
        for (var email : purchaseOrderEmailRepository.findAllByExpectedDeliveryIdInOrderByCreatedAtDesc(ids)) {
            latest.putIfAbsent(email.getExpectedDeliveryId(), email);
        }
        return latest;
    }

    private ExpectedDeliveryResponse respond(
            ExpectedDelivery expected,
            Map<UUID, Map<String, StockInTemplateRow>> waysToBuy,
            Map<UUID, com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmail> latestEmails,
            ExpectedDeliveryResponse.Share share,
            Map<UUID, String> names,
            List<ExpectedDeliveryResponse.Receipt> receipts) {
        List<ExpectedDeliveryResponse.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean priced = false;
        int outstandingLines = 0;
        for (ExpectedDeliveryLine line : expected.getLines()) {
            UUID productId = line.getProduct().getId();
            StockInTemplateRow row = Optional.ofNullable(waysToBuy.get(productId))
                    .map(byUnit -> byUnit.get(line.getUnit()))
                    .orElse(null);
            BigDecimal outstanding = line.outstanding();
            if (outstanding.signum() > 0) {
                outstandingLines++;
            }
            if (line.getPrice() != null) {
                total = total.add(line.getPrice().multiply(line.getQuantity()).setScale(2, java.math.RoundingMode.HALF_UP));
                priced = true;
            }
            lines.add(new ExpectedDeliveryResponse.Line(
                    line.getId(),
                    productId,
                    // The product may have been renamed, or its packs changed, since this was
                    // written down. The catalog's word is the current one; the stored unit key is
                    // still what gets sent back when receiving.
                    row != null ? row.productName() : line.getProduct().getName(),
                    row != null ? row.sku() : line.getProduct().getSku(),
                    line.getUnit(),
                    row != null
                            ? SheetUnitOptions.comesInLabel(row.option())
                            : UnitOptions.spokenPhraseOfSubmitted(line.getUnit()),
                    line.getQuantity(),
                    line.getReceivedQuantity(),
                    outstanding,
                    line.getPrice()));
        }
        String vendorName = expected.getVendor() == null ? null : expected.getVendor().getName();
        // The total is the lines plus delivery; with no price anywhere there is no total to show.
        if (expected.getDeliveryCharge() != null) {
            total = total.add(expected.getDeliveryCharge());
        }
        BigDecimal shownTotal = priced ? total : null;
        return new ExpectedDeliveryResponse(
                expected.getId(),
                expected.getStatus(),
                title(expected.getPoNumber(), vendorName, expected.getExpectedDate()),
                expected.getVendor() == null ? null : expected.getVendor().getId(),
                vendorName,
                expected.getExpectedDate(),
                expected.getReference(),
                expected.getNote(),
                lines,
                outstandingLines,
                shownTotal,
                expected.getStatus() == ExpectedDeliveryStatus.OPEN && outstandingLines > 0,
                expected.getCreatedAt(),
                expected.getBranchId(),
                expected.getPoNumber(),
                expected.getRevision(),
                emailOf(latestEmails.get(expected.getId())),
                share,
                expected.getCreatedBy() == null ? null : names.get(expected.getCreatedBy()),
                expected.getSentAt(),
                expected.getSentVia(),
                expected.getStatus() == ExpectedDeliveryStatus.OPEN && expected.isWaitingToBeSent(),
                receipts,
                expected.getDeliveryCharge(),
                expected.getApproval() == null
                        ? null
                        : new ExpectedDeliveryResponse.Approval(
                                expected.getApproval(),
                                expected.getApprovalAskedBy() == null ? null : names.get(expected.getApprovalAskedBy()),
                                expected.getApprovalAskedAt(),
                                expected.getApprovalNote(),
                                expected.getApprovedBy() == null ? null : names.get(expected.getApprovedBy()),
                                expected.getApprovedAt(),
                                expected.getChangeAskedBy() == null ? null : names.get(expected.getChangeAskedBy()),
                                expected.getChangeAskedAt(),
                                expected.getChangeNote(),
                                expected.getApprovalAskedBy()));
    }

    private static ExpectedDeliveryResponse.Email emailOf(
            com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmail email) {
        return email == null
                ? null
                : new ExpectedDeliveryResponse.Email(
                        email.getStatus(), email.getToAddress(), email.getCreatedAt(), email.getProblem());
    }

    /** A purchase order leads with its number: "PO-0007, Tony Stores, due 12 Nov". */
    static String title(String poNumber, String vendorName, LocalDate expectedDate) {
        String base = title(vendorName, expectedDate);
        return poNumber == null ? base : poNumber + ", " + base;
    }

    /** "Tony Stores, due 22 Sep" - or just the supplier, or just the date, or "Expected delivery". */
    static String title(String vendorName, LocalDate expectedDate) {
        String due = expectedDate == null ? null : "due " + expectedDate.format(DUE_FORMAT);
        if (vendorName == null) {
            return due == null ? "Expected delivery" : "Expected delivery, " + due;
        }
        return due == null ? vendorName : vendorName + ", " + due;
    }

    /** Every way each of these products is bought, keyed by product and then by unit key. */
    private Map<UUID, Map<String, StockInTemplateRow>> waysToBuy(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, StockInTemplateRow>> byProduct = new LinkedHashMap<>();
        for (StockInTemplateRow row : stockInTemplateService.rows(productIds, null, null, null)) {
            byProduct.computeIfAbsent(row.productId(), key -> new LinkedHashMap<>())
                    .putIfAbsent(UnitOptions.key(row.option()), row);
        }
        return byProduct;
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new ExpectedDeliveryException(HttpStatus.BAD_REQUEST, "We could not read that date.");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
