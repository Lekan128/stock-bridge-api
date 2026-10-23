package com.procurepal_services.stock_bridge_api.transfer;

import com.procurepal_services.stock_bridge_api.branch.BranchAccessService;
import com.procurepal_services.stock_bridge_api.branch.BranchNotFoundException;
import com.procurepal_services.stock_bridge_api.branch.BranchStockService;
import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.entity.StockTransfer;
import com.procurepal_services.stock_bridge_api.entity.StockTransferLine;
import com.procurepal_services.stock_bridge_api.entity.StockTransferStatus;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
import com.procurepal_services.stock_bridge_api.product.ProductNotFoundException;
import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.repository.StockTransferRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.transfer.dto.ReceiptRequest;
import com.procurepal_services.stock_bridge_api.transfer.dto.TransferRequest;
import com.procurepal_services.stock_bridge_api.transfer.dto.TransferResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving stock between a company's branches (MULTI_BRANCH_BUILD_PLAN.md section 5, MULTI_BRANCH_UX_PLAN.md
 * sections 5.4-5.7). The ledger rows are written by {@link StockManagementService}, the one place they
 * are written; this decides who may do what to a waybill, in what order, and keeps its record.
 *
 * <h2>Locks, in one order</h2>
 * Sending locks the company (for the next number), then its products in id order - the order every
 * stock write locks them in (D-B2). Receiving locks the waybill, then its products in id order. Two
 * people receiving the same waybill queue on the waybill, and the second sees the first's receipt.
 *
 * <h2>Idempotent from the phone</h2>
 * The waybill's id and each receipt's id are made on the phone, so a send or a receipt resent after a
 * lost reply, or arriving late from a phone that was offline, is recorded once and answered with the
 * waybill as it stands.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    private final StockTransferRepository transferRepository;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final UserRepository userRepository;
    private final StockManagementService stockManagementService;
    private final BranchStockService branchStockService;
    private final BranchAccessService branchAccessService;
    private final StockWritePermissionGuard permissions;
    private final NotificationService notificationService;
    private final NamedParameterJdbcTemplate jdbc;

    // ------------------------------------------------------------------------------------- send

    @Transactional
    public TransferResponse send(TransferRequest request, UUID userId) {
        UUID clientId = requireTenantId();
        StockTransfer existing = transferRepository.findByIdAndClientId(request.id(), clientId).orElse(null);
        if (existing != null) {
            return respond(existing);
        }
        if (request.fromBranchId().equals(request.toBranchId())) {
            throw new TransferRefusedException("Choose a different branch to send to.");
        }
        UUID from = branchStockService.resolveForWrite(request.fromBranchId(), userId);
        UUID to = branchStockService.resolveForWrite(request.toBranchId(), userId);
        permissions.require(userId, "SEND_STOCK");
        permissions.require(userId, "STOCK_OUT");
        branchAccessService.requireMayRecordAt(userId, from);
        if (request.receivesNow()) {
            permissions.require(userId, "STOCK_IN");
            branchAccessService.requireMayRecordAt(userId, to);
        }

        // One send at a time per company: the next waybill number, and a code for a first-time sender.
        jdbc.queryForList("SELECT id FROM clients WHERE id = :clientId FOR UPDATE",
                new MapSqlParameterSource("clientId", clientId), UUID.class);
        Branch fromBranch = requireBranch(from, clientId);
        ensureCode(fromBranch, clientId);
        OffsetDateTime sentAt = request.occurredAt() != null ? request.occurredAt() : OffsetDateTime.now();
        StockTransfer transfer = StockTransfer.builder()
                .id(request.id())
                .number(transferRepository.maxNumber(clientId) + 1)
                .fromBranchId(from)
                .toBranchId(to)
                .status(StockTransferStatus.ON_THE_WAY)
                .sentBy(userId)
                .sentAt(sentAt)
                .carriedByName(blankToNull(request.carriedByName()))
                .carriedByPhone(blankToNull(request.carriedByPhone()))
                .note(blankToNull(request.note()))
                .build();
        transferRepository.saveAndFlush(transfer);
        String waybill = waybillNumber(fromBranch, transfer.getNumber());
        String toName = requireBranch(to, clientId).getName();

        Map<UUID, TransferRequest.Line> byProduct = new LinkedHashMap<>();
        for (TransferRequest.Line line : request.lines()) {
            if (byProduct.putIfAbsent(line.productId(), line) != null) {
                throw new TransferRefusedException("A product is on this waybill twice. Put it on one line.");
            }
        }
        for (UUID productId : byProduct.keySet().stream().sorted().toList()) {
            TransferRequest.Line line = byProduct.get(productId);
            Product product = productRepository.findByIdAndClientIdForUpdate(productId, clientId)
                    .orElseThrow(ProductNotFoundException::new);
            int quantity = stockManagementService.toStockUnitQuantity(product, line.quantity(), line.unit());
            boolean typedInAnotherUnit = line.unit() != null && !line.unit().equalsIgnoreCase(product.getUnitOfMeasure());
            StockMovement out = stockManagementService.transferOut(
                    product, from, quantity, transfer.getId(), sentAt, "Sent to " + toName + " on " + waybill,
                    typedInAnotherUnit ? line.unit() : null,
                    typedInAnotherUnit ? BigDecimal.valueOf(line.quantity()) : null,
                    userId);
            transfer.addLine(StockTransferLine.builder()
                    .product(product)
                    .quantitySent(quantity)
                    .enteredUnit(typedInAnotherUnit ? line.unit() : null)
                    .enteredQuantity(typedInAnotherUnit ? BigDecimal.valueOf(line.quantity()) : null)
                    .outMovementId(out.getId())
                    .build());
        }
        transferRepository.saveAndFlush(transfer);
        log.info("Transfer sent: {} {} from {} to {} for company {} by {}", transfer.getId(), waybill, from, to, clientId, userId);

        if (request.receivesNow()) {
            receiveRemaining(transfer, sentAt, userId, null);
        } else {
            notifyOnTheWay(transfer, fromBranch, toName, waybill, userId);
        }
        return respond(transfer);
    }

    // ---------------------------------------------------------------------------------- receive

    @Transactional
    public TransferResponse receive(UUID transferId, ReceiptRequest request, UUID userId) {
        UUID clientId = requireTenantId();
        StockTransfer transfer = transferRepository.findForUpdate(transferId, clientId)
                .orElseThrow(TransferNotFoundException::new);
        if (receiptExists(request.id())) {
            return respond(transfer);
        }
        requireOpen(transfer);
        permissions.require(userId, "STOCK_IN");
        branchAccessService.requireMayRecordAt(userId, transfer.getToBranchId());
        OffsetDateTime at = request.occurredAt() != null ? request.occurredAt() : OffsetDateTime.now();

        if (request.receivesAsSent()) {
            receiveRemaining(transfer, at, userId, request.id());
            return respond(transfer);
        }
        Map<UUID, ReceiptRequest.Line> asked = new HashMap<>();
        for (ReceiptRequest.Line line : request.lines() == null ? List.<ReceiptRequest.Line>of() : request.lines()) {
            asked.put(line.productId(), line);
        }
        for (UUID productId : asked.keySet()) {
            if (lineFor(transfer, productId) == null) {
                throw new TransferRefusedException("Something on this receipt isn't on the waybill.");
            }
        }
        String waybill = waybillOf(transfer);
        int losses = 0;
        for (StockTransferLine line : linesInLockOrder(transfer)) {
            ReceiptRequest.Line got = asked.get(line.getProduct().getId());
            if (got == null) {
                continue;
            }
            int received = valueOf(got.received());
            int missing = valueOf(got.missing());
            int damaged = valueOf(got.damaged());
            int remaining = line.onTheWay();
            int surplus = 0;
            if (received + missing + damaged > remaining) {
                if (missing + damaged > 0) {
                    throw new TransferRefusedException(line.getProduct().getName() + ": that is more than is still on the way ("
                            + remaining + "). Check the numbers.");
                }
                // "Sent too many": the sending branch had more than its books said.
                surplus = received - remaining;
                received = remaining;
            }
            Product product = productRepository.findByIdAndClientIdForUpdate(line.getProduct().getId(), clientId)
                    .orElseThrow(ProductNotFoundException::new);
            StockMovement out = outMovementOf(line);
            int offset = line.accounted();
            stockManagementService.transferIn(product, transfer.getToBranchId(),
                    stockManagementService.transferPieces(out, offset, received), transfer.getId(), at,
                    "Received from " + branchName(transfer.getFromBranchId()) + " on " + waybill, userId);
            if (missing + damaged > 0) {
                stockManagementService.transferLoss(product,
                        stockManagementService.transferPieces(out, offset + received, missing + damaged));
            }
            if (surplus > 0) {
                stockManagementService.transferSurplus(product, transfer.getFromBranchId(), transfer.getToBranchId(), surplus,
                        transfer.getId(), waybill, at, userId);
            }
            if (surplus > 0) {
                // The extra came on this waybill too: what left is now what was sent, and the line stays whole.
                adjustSentForSurplus(line, surplus);
            }
            line.setQuantityReceived(line.getQuantityReceived() + received + surplus);
            line.setQuantityMissing(line.getQuantityMissing() + missing);
            line.setQuantityDamaged(line.getQuantityDamaged() + damaged);
            losses += missing + damaged;
        }
        settle(transfer, at, userId, request.id());
        if (losses > 0) {
            notifyShort(transfer, waybill, userId);
        }
        return respond(transfer);
    }

    /**
     * Whatever is still on the way does not come (UX section 5.5, "Close as missing"): the rest of every
     * line is missing, and stays in view until checked.
     */
    @Transactional
    public TransferResponse close(UUID transferId, UUID userId) {
        UUID clientId = requireTenantId();
        StockTransfer transfer = transferRepository.findForUpdate(transferId, clientId)
                .orElseThrow(TransferNotFoundException::new);
        requireOpen(transfer);
        boolean sender = userId.equals(transfer.getSentBy());
        if (!sender && !(branchAccessService.mayRecordAt(userId, transfer.getFromBranchId())
                && branchAccessService.mayRecordAt(userId, transfer.getToBranchId()))) {
            throw new TransferRefusedException("Only the sender, or someone who works at both branches, can close this.");
        }
        int losses = 0;
        for (StockTransferLine line : linesInLockOrder(transfer)) {
            int remaining = line.onTheWay();
            if (remaining <= 0) {
                continue;
            }
            Product product = productRepository.findByIdAndClientIdForUpdate(line.getProduct().getId(), clientId)
                    .orElseThrow(ProductNotFoundException::new);
            stockManagementService.transferLoss(product,
                    stockManagementService.transferPieces(outMovementOf(line), line.accounted(), remaining));
            line.setQuantityMissing(line.getQuantityMissing() + remaining);
            losses += remaining;
        }
        settle(transfer, OffsetDateTime.now(), null, null);
        if (losses > 0) {
            notifyShort(transfer, waybillOf(transfer), userId);
        }
        return respond(transfer);
    }

    /**
     * Takes a waybill back before anything has arrived (UX section 5.4's Undo, and later): the stock is
     * on the sending shelf again, through compensating rows - nothing is deleted.
     */
    @Transactional
    public TransferResponse cancel(UUID transferId, UUID userId) {
        UUID clientId = requireTenantId();
        StockTransfer transfer = transferRepository.findForUpdate(transferId, clientId)
                .orElseThrow(TransferNotFoundException::new);
        if (transfer.getStatus() == StockTransferStatus.CANCELLED) {
            return respond(transfer);
        }
        if (transfer.getStatus() != StockTransferStatus.ON_THE_WAY
                || transfer.getLines().stream().anyMatch(line -> line.accounted() > 0)) {
            throw new TransferRefusedException("Some of this has already been received, so it can't be cancelled. "
                    + "Close what's left as missing, or send the stock back on a new transfer.");
        }
        boolean sender = userId.equals(transfer.getSentBy());
        if (!sender && !permissions.has(userId, "MANAGE_BRANCHES")) {
            throw new TransferRefusedException("Only the person who sent this can cancel it.");
        }
        for (StockTransferLine line : linesInLockOrder(transfer)) {
            Product product = productRepository.findByIdAndClientIdForUpdate(line.getProduct().getId(), clientId)
                    .orElseThrow(ProductNotFoundException::new);
            stockManagementService.cancelTransferOut(product, outMovementOf(line), userId);
        }
        transfer.setStatus(StockTransferStatus.CANCELLED);
        log.info("Transfer cancelled: {} for company {} by {}", transferId, clientId, userId);
        return respond(transfer);
    }

    /**
     * Somebody who works at both branches says what happened to the missing stock (UX section 5.7):
     * "Driver paid for it", "Found in the van". It then leaves Needs you today.
     */
    @Transactional
    public TransferResponse checkMissing(UUID transferId, String note, UUID userId) {
        UUID clientId = requireTenantId();
        StockTransfer transfer = transferRepository.findForUpdate(transferId, clientId)
                .orElseThrow(TransferNotFoundException::new);
        if (!transfer.hasLosses()) {
            throw new TransferRefusedException("Nothing on this transfer is missing.");
        }
        if (!(branchAccessService.mayRecordAt(userId, transfer.getFromBranchId())
                && branchAccessService.mayRecordAt(userId, transfer.getToBranchId()))) {
            throw new TransferRefusedException("Someone who works at both branches checks missing stock.");
        }
        if (note == null || note.isBlank()) {
            throw new TransferRefusedException("Say what happened to it, so the note explains the loss later.");
        }
        transfer.setMissingCheckedBy(userId);
        transfer.setMissingCheckedAt(OffsetDateTime.now());
        transfer.setMissingNote(note.trim());
        return respond(transfer);
    }

    // ------------------------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public Page<TransferResponse> list(UUID userId, UUID branchId, boolean openOnly, Pageable pageable) {
        UUID clientId = requireTenantId();
        List<UUID> scope = branchAccessService.reportScope(userId, branchId);
        if (scope.isEmpty()) {
            return Page.empty(pageable);
        }
        return transferRepository.findTouching(clientId, scope, openOnly, pageable).map(this::respond);
    }

    @Transactional(readOnly = true)
    public TransferResponse get(UUID transferId, UUID userId) {
        StockTransfer transfer = transferRepository.findByIdAndClientId(transferId, requireTenantId())
                .orElseThrow(TransferNotFoundException::new);
        List<UUID> visible = branchAccessService.viewBranchIds(userId);
        if (!visible.contains(transfer.getFromBranchId()) && !visible.contains(transfer.getToBranchId())) {
            throw new TransferNotFoundException();
        }
        return respond(transfer);
    }

    // ---------------------------------------------------------------------------------- helpers

    /** "Received as sent", and both ends at once: every line's rest, received. */
    private void receiveRemaining(StockTransfer transfer, OffsetDateTime at, UUID userId, UUID receiptId) {
        UUID clientId = transfer.getClientId();
        String note = "Received from " + branchName(transfer.getFromBranchId()) + " on " + waybillOf(transfer);
        for (StockTransferLine line : linesInLockOrder(transfer)) {
            int remaining = line.onTheWay();
            if (remaining <= 0) {
                continue;
            }
            Product product = productRepository.findByIdAndClientIdForUpdate(line.getProduct().getId(), clientId)
                    .orElseThrow(ProductNotFoundException::new);
            stockManagementService.transferIn(product, transfer.getToBranchId(),
                    stockManagementService.transferPieces(outMovementOf(line), line.accounted(), remaining),
                    transfer.getId(), at, note, userId);
            line.setQuantityReceived(line.getQuantityReceived() + remaining);
        }
        settle(transfer, at, userId, receiptId);
    }

    private void settle(StockTransfer transfer, OffsetDateTime at, UUID userId, UUID receiptId) {
        if (transfer.isAccountedFor()) {
            transfer.setStatus(transfer.hasLosses() ? StockTransferStatus.RECEIVED_SHORT : StockTransferStatus.RECEIVED);
        } else if (transfer.getLines().stream().anyMatch(line -> line.accounted() > 0)) {
            transfer.setStatus(StockTransferStatus.PARTLY_RECEIVED);
        }
        if (userId != null) {
            transfer.setReceivedBy(userId);
            transfer.setReceivedAt(at);
        }
        transferRepository.saveAndFlush(transfer);
        if (receiptId != null) {
            jdbc.update("""
                    INSERT INTO stock_transfer_receipts (id, transfer_id, received_by, received_at)
                    VALUES (:id, :transferId, :userId, :at)
                    """, new MapSqlParameterSource()
                    .addValue("id", receiptId)
                    .addValue("transferId", transfer.getId())
                    .addValue("userId", userId)
                    .addValue("at", at));
        }
    }

    /**
     * A surplus came on this waybill: what was sent is now what arrived, so the line stays whole
     * (received never exceeds sent - the table's own check). The sending branch's books were corrected
     * by the same receipt ({@code transferSurplus}).
     */
    private void adjustSentForSurplus(StockTransferLine line, int surplus) {
        jdbc.update("UPDATE stock_transfer_lines SET quantity_sent = quantity_sent + :surplus WHERE id = :id",
                new MapSqlParameterSource().addValue("surplus", surplus).addValue("id", line.getId()));
        line.setQuantitySent(line.getQuantitySent() + surplus);
    }

    private void requireOpen(StockTransfer transfer) {
        switch (transfer.getStatus()) {
            case ON_THE_WAY, PARTLY_RECEIVED -> {
            }
            case CANCELLED -> throw new TransferRefusedException("This transfer was cancelled, so there is nothing to receive.");
            default -> throw new TransferRefusedException(receivedBySentence(transfer));
        }
    }

    private String receivedBySentence(StockTransfer transfer) {
        String who = transfer.getReceivedBy() == null ? null : displayName(transfer.getReceivedBy());
        String when = transfer.getReceivedAt() == null ? null : WHEN.format(transfer.getReceivedAt());
        return "This was already received" + (who != null ? " by " + who : "") + (when != null ? " on " + when : "") + ".";
    }

    private boolean receiptExists(UUID receiptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stock_transfer_receipts WHERE id = :id)",
                new MapSqlParameterSource("id", receiptId), Boolean.class));
    }

    private static StockTransferLine lineFor(StockTransfer transfer, UUID productId) {
        return transfer.getLines().stream().filter(line -> line.getProduct().getId().equals(productId)).findFirst().orElse(null);
    }

    private static List<StockTransferLine> linesInLockOrder(StockTransfer transfer) {
        return transfer.getLines().stream().sorted(Comparator.comparing(line -> line.getProduct().getId())).toList();
    }

    private StockMovement outMovementOf(StockTransferLine line) {
        return stockMovementRepository.findById(line.getOutMovementId()).orElseThrow();
    }

    /** A first-time sender gets a short code from its name: "Bolade" is BOL, a clash adds a digit. */
    private void ensureCode(Branch branch, UUID clientId) {
        if (branch.getCode() != null) {
            return;
        }
        Set<String> taken = new HashSet<>();
        for (Branch other : branchRepository.findAllByClientId(clientId)) {
            if (other.getCode() != null) taken.add(other.getCode());
        }
        String letters = branch.getName().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        String base = letters.isEmpty() ? "BR" : letters.substring(0, Math.min(3, letters.length()));
        String code = base;
        for (int n = 2; taken.contains(code); n++) {
            code = base + n;
        }
        branch.setCode(code);
        branchRepository.saveAndFlush(branch);
    }

    private String waybillNumber(Branch from, int number) {
        return String.format(Locale.ROOT, "WB-%s-%04d", from.getCode() == null ? "BR" : from.getCode(), number);
    }

    private String waybillOf(StockTransfer transfer) {
        return waybillNumber(requireBranch(transfer.getFromBranchId(), transfer.getClientId()), transfer.getNumber());
    }

    private Branch requireBranch(UUID branchId, UUID clientId) {
        return branchRepository.findByIdAndClientId(branchId, clientId).orElseThrow(BranchNotFoundException::new);
    }

    private String branchName(UUID branchId) {
        return branchRepository.findById(branchId).map(Branch::getName).orElse("another branch");
    }

    private String displayName(UUID userId) {
        return userRepository.findById(userId).map(TransferService::nameOf).orElse(null);
    }

    private static String nameOf(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }

    /** "On the way" to the people who work at the receiving branch - not those there only by role. */
    private void notifyOnTheWay(StockTransfer transfer, Branch from, String toName, String waybill, UUID senderId) {
        List<UUID> people = jdbc.queryForList("""
                SELECT ub.user_id FROM user_branches ub JOIN users u ON u.id = ub.user_id
                WHERE ub.branch_id = :branchId AND u.is_active AND u.id <> :sender
                """, new MapSqlParameterSource().addValue("branchId", transfer.getToBranchId()).addValue("sender", senderId),
                UUID.class);
        String sender = displayName(senderId);
        String title = itemsPhrase(transfer) + " on the way from " + from.getName();
        String body = waybill + " · sent " + WHEN.format(transfer.getSentAt()) + (sender != null ? " by " + sender : "")
                + (transfer.getCarriedByName() != null ? " · carried by " + transfer.getCarriedByName() : "");
        for (UUID person : people) {
            notificationService.notifyClient(transfer.getClientId(), person, NotificationType.TRANSFER_ON_THE_WAY,
                    title, body, "/app/transfers/" + transfer.getId(), null);
        }
    }

    /** Received short: to the sender and the account holder (UX section 5.13). */
    private void notifyShort(StockTransfer transfer, String waybill, UUID receiverId) {
        Set<UUID> people = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM users WHERE client_id = :clientId AND is_root AND is_active",
                new MapSqlParameterSource("clientId", transfer.getClientId()), UUID.class));
        if (transfer.getSentBy() != null) people.add(transfer.getSentBy());
        people.remove(receiverId);
        int missing = transfer.getLines().stream().mapToInt(StockTransferLine::getQuantityMissing).sum();
        int damaged = transfer.getLines().stream().mapToInt(StockTransferLine::getQuantityDamaged).sum();
        String title = waybill + " arrived short at " + branchName(transfer.getToBranchId());
        String body = (missing > 0 ? missing + " missing" : "") + (missing > 0 && damaged > 0 ? ", " : "")
                + (damaged > 0 ? damaged + " damaged" : "");
        for (UUID person : people) {
            notificationService.notifyClient(transfer.getClientId(), person, NotificationType.TRANSFER_RECEIVED_SHORT,
                    title, body, "/app/transfers/" + transfer.getId(), null);
        }
    }

    private static String itemsPhrase(StockTransfer transfer) {
        int count = transfer.getLines().size();
        return count == 1 ? "1 item" : count + " items";
    }

    private TransferResponse respond(StockTransfer transfer) {
        String fromName = branchName(transfer.getFromBranchId());
        String toName = branchName(transfer.getToBranchId());
        String waybill = waybillOf(transfer);
        List<TransferResponse.Line> lines = new ArrayList<>();
        for (StockTransferLine line : transfer.getLines()) {
            Product product = line.getProduct();
            lines.add(new TransferResponse.Line(
                    product.getId(), product.getName(), product.getSku(), product.getUnitOfMeasure(),
                    line.getQuantitySent(), line.getQuantityReceived(), line.getQuantityMissing(), line.getQuantityDamaged(),
                    line.onTheWay(), line.getEnteredUnit(), line.getEnteredQuantity()));
        }
        int missing = transfer.getMissingCheckedAt() != null ? 0
                : transfer.getLines().stream().mapToInt(line -> line.getQuantityMissing() + line.getQuantityDamaged()).sum();
        String sentBy = transfer.getSentBy() == null ? null : displayName(transfer.getSentBy());
        return new TransferResponse(
                transfer.getId(), waybill, transfer.getNumber(),
                transfer.getFromBranchId(), fromName, transfer.getToBranchId(), toName,
                transfer.getStatus(), sentBy, transfer.getSentAt(),
                transfer.getCarriedByName(), transfer.getCarriedByPhone(), transfer.getNote(),
                transfer.getReceivedBy() == null ? null : displayName(transfer.getReceivedBy()), transfer.getReceivedAt(),
                missing,
                transfer.getMissingCheckedBy() == null ? null : displayName(transfer.getMissingCheckedBy()),
                transfer.getMissingCheckedAt(), transfer.getMissingNote(),
                lines,
                shareText(waybill, fromName, toName, sentBy, transfer, lines));
    }

    /** The waybill as plain text for WhatsApp (UX section 5.4): readable in a chat on any phone. */
    private static String shareText(
            String waybill, String from, String to, String sentBy, StockTransfer transfer, List<TransferResponse.Line> lines) {
        StringBuilder text = new StringBuilder()
                .append("Waybill ").append(waybill).append('\n')
                .append("From ").append(from).append(" to ").append(to).append('\n')
                .append("Sent ").append(WHEN.format(transfer.getSentAt()));
        if (sentBy != null) text.append(" by ").append(sentBy);
        text.append('\n');
        if (transfer.getCarriedByName() != null) {
            text.append("Carried by ").append(transfer.getCarriedByName());
            if (transfer.getCarriedByPhone() != null) text.append(" (").append(transfer.getCarriedByPhone()).append(')');
            text.append('\n');
        }
        text.append('\n');
        for (TransferResponse.Line line : lines) {
            String amount = line.enteredQuantity() != null
                    ? line.enteredQuantity().stripTrailingZeros().toPlainString() + " " + line.enteredUnit().toLowerCase(Locale.ROOT)
                    : line.quantitySent() + (line.unitOfMeasure() == null ? "" : " " + line.unitOfMeasure().toLowerCase(Locale.ROOT));
            text.append("- ").append(line.productName()).append(": ").append(amount).append('\n');
        }
        if (transfer.getNote() != null) {
            text.append('\n').append("Note: ").append(transfer.getNote()).append('\n');
        }
        text.append('\n').append("Received by: ____________________");
        return text.toString();
    }

    private static int valueOf(Integer value) {
        return value == null ? 0 : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context is set");
        }
        return tenantId;
    }
}
