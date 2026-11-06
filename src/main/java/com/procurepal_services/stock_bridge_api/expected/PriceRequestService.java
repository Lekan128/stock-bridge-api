package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.email.EmailProperties;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryRequest;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
import com.procurepal_services.stock_bridge_api.product.bulk.SheetUnitOptions;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateRow;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateService;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.push.PushMessage;
import com.procurepal_services.stock_bridge_api.push.PushService;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asking several suppliers for prices (PURCHASE_ORDER_PLAN.md section 11.3, V80; owner 2026-11-06).
 *
 * <p>A request (RFQ-0003) lists what is wanted and who should quote. Each supplier gets their own
 * unguessable link to type their prices into, with a remark, and no account; a supplier who phones
 * instead has their prices typed in by the buyer. Each reply raises a notification. Choosing turns
 * the replies into ordinary purchase orders - one per supplier chosen - which then follow the usual
 * approval and send rules of whoever chose.
 *
 * <p>Plain SQL, like Stock requests: the records are read as whole screens, never as entities.
 * Authenticated calls are scoped by the tenant; the public ones by token alone (no tenant there).
 */
@Service
@RequiredArgsConstructor
public class PriceRequestService {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000000");

    private final NamedParameterJdbcTemplate jdbc;
    private final StockInTemplateService stockInTemplateService;
    private final StockWritePermissionGuard permissions;
    private final ExpectedDeliveryService expectedDeliveryService;
    private final NotificationService notificationService;
    private final PushService pushService;
    private final UserRepository userRepository;
    private final EmailProperties emailProperties;
    private final JsonMapper jsonMapper;

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------------------- shapes ----

    public record NewLine(UUID productId, String unit, BigDecimal quantity) {
    }

    public record NewRequest(List<NewLine> lines, List<UUID> vendorIds, LocalDate neededBy, LocalDate replyBy,
            UUID branchId, String note) {
    }

    /** One line's answer: a price for ONE of the unit, or "I don't have this". */
    public record LineReply(UUID lineId, BigDecimal price, boolean cannotSupply) {
    }

    /**
     * @param canDeliverOn the day they can bring it; null when not said.
     * @param declined "Tony can't do it" - nothing on the request.
     */
    public record Reply(List<LineReply> lines, BigDecimal deliveryCharge, LocalDate canDeliverOn, String remark,
            boolean declined) {
    }

    public record Line(UUID id, UUID productId, String productName, String unit, String comesIn, BigDecimal quantity) {
    }

    public record Quote(UUID lineId, BigDecimal price, boolean cannotSupply, BigDecimal amount) {
    }

    /**
     * @param status NOT_SENT, SENT, OPENED, REPLIED or DECLINED - what the request page says per supplier.
     * @param total the priced lines plus delivery; absent until they reply.
     * @param complete they priced every line.
     */
    public record Supplier(UUID id, UUID vendorId, String vendorName, String phone, String email, String status,
            String sentVia, OffsetDateTime sentAt, OffsetDateTime openedAt, OffsetDateTime repliedAt,
            String replySource, boolean changed, BigDecimal deliveryCharge, LocalDate canDeliverOn, String remark,
            List<Quote> quotes, BigDecimal total, boolean complete, UUID orderId, String orderNumber,
            String url, String message, String whatsappUrl) {
    }

    /** One way to order, for "Order from". {@code kind} CHEAPEST or SUPPLIER. */
    public record Option(String kind, UUID supplierId, BigDecimal total, int orders, List<String> suppliers,
            int linesCovered) {
    }

    /**
     * @param repliesClosed the Reply by day has passed (section 12.1): suppliers' links take no more prices.
     * @param picked once it became orders: which supplier each item went to, at what price, and the lowest
     *     offered then - so a dearer pick can be explained later (section 12.2).
     */
    public record Request(UUID id, String rfqNumber, String status, LocalDate neededBy, LocalDate replyBy,
            UUID branchId, String note, String createdByName, OffsetDateTime createdAt, List<Line> lines,
            List<Supplier> suppliers, Map<UUID, UUID> lowestByLine, List<Option> options, boolean repliesClosed,
            List<Picked> picked) {
    }

    /** One item of a request that became orders: who it went to, at what price, and the lowest price offered. */
    public record Picked(UUID lineId, UUID supplierId, String vendorName, BigDecimal price, BigDecimal lowestPrice,
            String lowestVendorName, String orderNumber) {
    }

    /** One item's choice when making orders (section 12.2): a supplier who priced it, or null for "Not now". */
    public record Pick(UUID lineId, UUID supplierId) {
    }

    /** A row on the Purchase orders list's "Asking for prices" chip. */
    public record Row(UUID id, String rfqNumber, String status, OffsetDateTime createdAt, List<String> products,
            int suppliers, int replied, List<String> orderNumbers, LocalDate replyBy, boolean repliesClosed) {
    }

    public record ChooseResult(List<UUID> orderIds) {
    }

    // ------------------------------------------------------------------------------- create ----

    @Transactional
    public Request create(NewRequest request, UUID userId) {
        UUID tenantId = requireTenantId();
        requireMayOrder(userId);
        if (request.lines() == null || request.lines().isEmpty()) {
            throw bad("Add at least one item to ask about.");
        }
        List<UUID> vendorIds = request.vendorIds() == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(request.vendorIds()));
        if (vendorIds.isEmpty()) {
            throw bad("Pick at least one supplier to ask.");
        }
        if (vendorIds.size() > 10) {
            throw bad("Ask at most 10 suppliers at once.");
        }
        // Replies close at the end of this day (section 12.1), so it's always set.
        if (request.replyBy() == null) {
            throw bad("Say when suppliers should reply by.");
        }
        if (request.replyBy().isBefore(today())) {
            throw bad("Reply by can't be in the past.");
        }
        Integer known = jdbc.queryForObject(
                "SELECT COUNT(*) FROM company_vendors WHERE client_id = :clientId AND is_active = TRUE AND id IN (:ids)",
                new MapSqlParameterSource().addValue("clientId", tenantId).addValue("ids", vendorIds), Integer.class);
        if (known == null || known != vendorIds.size()) {
            throw bad("One of those suppliers is no longer in your list.");
        }
        Map<UUID, Map<String, StockInTemplateRow>> ways = waysToBuy(
                request.lines().stream().map(NewLine::productId).distinct().toList());

        entityManager.createNativeQuery("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 73))) AS l")
                .setParameter("key", "rfq:" + tenantId).getSingleResult();
        Integer highest = jdbc.queryForObject("SELECT MAX(rfq_sequence) FROM price_requests WHERE client_id = :clientId",
                new MapSqlParameterSource("clientId", tenantId), Integer.class);
        int sequence = (highest == null ? 0 : highest) + 1;
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO price_requests (id, client_id, rfq_sequence, rfq_number, branch_id, needed_by, reply_by, note,
                                            status, created_by, created_at, updated_at)
                VALUES (:id, :clientId, :sequence, :number, :branchId, :neededBy, :replyBy, :note, 'OPEN', :userId, :now, :now)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("clientId", tenantId).addValue("sequence", sequence)
                .addValue("number", String.format("RFQ-%04d", sequence)).addValue("branchId", request.branchId())
                .addValue("neededBy", request.neededBy() == null ? null : Date.valueOf(request.neededBy()))
                .addValue("replyBy", request.replyBy() == null ? null : Date.valueOf(request.replyBy()))
                .addValue("note", blankToNull(request.note())).addValue("userId", userId)
                .addValue("now", Timestamp.from(now.toInstant())));
        int position = 0;
        Set<String> seen = new java.util.HashSet<>();
        for (NewLine line : request.lines()) {
            if (line.quantity() == null || line.quantity().signum() <= 0) {
                throw bad("Say how many of each.");
            }
            StockInTemplateRow row = ways.getOrDefault(line.productId(), Map.of()).get(line.unit());
            if (row == null) {
                throw bad("One of these products is no longer in your catalog.");
            }
            if (!seen.add(line.productId() + "|" + line.unit())) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO price_request_lines (id, request_id, product_id, unit, quantity, product_name, comes_in, position)
                    VALUES (:id, :requestId, :productId, :unit, :quantity, :name, :comesIn, :position)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID()).addValue("requestId", id).addValue("productId", line.productId())
                    .addValue("unit", line.unit()).addValue("quantity", line.quantity())
                    .addValue("name", row.productName()).addValue("comesIn", SheetUnitOptions.comesInLabel(row.option()))
                    .addValue("position", position++));
        }
        for (UUID vendorId : vendorIds) {
            jdbc.update("""
                    INSERT INTO price_request_suppliers (id, request_id, vendor_id, token)
                    VALUES (:id, :requestId, :vendorId, :token)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID()).addValue("requestId", id).addValue("vendorId", vendorId)
                    .addValue("token", PurchaseOrderSharing.newToken()));
        }
        return get(id);
    }

    // --------------------------------------------------------------------------------- read ----

    @Transactional(readOnly = true)
    public List<Row> list(String status, int limit) {
        UUID tenantId = requireTenantId();
        List<Map<String, Object>> requests = jdbc.queryForList("""
                SELECT r.id, r.rfq_number, r.status, r.created_at, r.reply_by,
                       (SELECT COUNT(*) FROM price_request_suppliers s WHERE s.request_id = r.id) AS suppliers,
                       (SELECT COUNT(*) FROM price_request_suppliers s WHERE s.request_id = r.id
                         AND (s.replied_at IS NOT NULL OR s.declined)) AS replied
                FROM price_requests r
                WHERE r.client_id = :clientId AND (:status = 'ALL' OR r.status = :status)
                ORDER BY r.created_at DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("clientId", tenantId)
                .addValue("status", status == null ? "OPEN" : status).addValue("limit", Math.min(Math.max(limit, 1), 100)));
        List<Row> rows = new ArrayList<>();
        for (Map<String, Object> request : requests) {
            UUID id = (UUID) request.get("id");
            List<String> products = jdbc.queryForList(
                    "SELECT product_name FROM price_request_lines WHERE request_id = :id ORDER BY position LIMIT 4",
                    new MapSqlParameterSource("id", id), String.class);
            List<String> orders = jdbc.queryForList(
                    "SELECT po_number FROM expected_deliveries WHERE price_request_id = :id AND client_id = :clientId ORDER BY po_number",
                    new MapSqlParameterSource().addValue("id", id).addValue("clientId", tenantId), String.class);
            rows.add(new Row(id, (String) request.get("rfq_number"), (String) request.get("status"),
                    offset(request.get("created_at")), products, ((Number) request.get("suppliers")).intValue(),
                    ((Number) request.get("replied")).intValue(), orders, date(request.get("reply_by")),
                    repliesClosed(date(request.get("reply_by")))));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public long openCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM price_requests WHERE client_id = :clientId AND status = 'OPEN'",
                new MapSqlParameterSource("clientId", requireTenantId()), Long.class);
        return count == null ? 0 : count;
    }

    @Transactional(readOnly = true)
    public Request get(UUID id) {
        UUID tenantId = requireTenantId();
        Map<String, Object> request = one("""
                SELECT r.*, u.first_name, u.last_name, u.username, c.name AS company_name
                FROM price_requests r JOIN clients c ON c.id = r.client_id
                LEFT JOIN users u ON u.id = r.created_by
                WHERE r.id = :id AND r.client_id = :clientId
                """, new MapSqlParameterSource().addValue("id", id).addValue("clientId", tenantId));
        if (request == null) {
            throw new ExpectedDeliveryException(HttpStatus.NOT_FOUND, "We couldn't find that price request.");
        }
        List<Line> lines = lines(id);
        Map<UUID, Line> lineById = new LinkedHashMap<>();
        lines.forEach(line -> lineById.put(line.id(), line));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.*, v.name AS vendor_name, v.contact_phone, v.email, e.po_number,
                       (SELECT COUNT(*) FROM price_request_reply_history h WHERE h.supplier_row_id = s.id) AS replies
                FROM price_request_suppliers s JOIN company_vendors v ON v.id = s.vendor_id
                LEFT JOIN expected_deliveries e ON e.id = s.order_id
                WHERE s.request_id = :id ORDER BY v.name
                """, new MapSqlParameterSource("id", id));
        Map<UUID, Map<UUID, Quote>> quotes = quotes(id, lineById);
        String buyer = (String) request.get("company_name");
        String rfq = (String) request.get("rfq_number");
        LocalDate replyBy = date(request.get("reply_by"));
        List<Supplier> suppliers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID rowId = (UUID) row.get("id");
            Map<UUID, Quote> theirs = quotes.getOrDefault(rowId, Map.of());
            boolean replied = row.get("replied_at") != null;
            boolean declined = Boolean.TRUE.equals(row.get("declined"));
            BigDecimal charge = (BigDecimal) row.get("delivery_charge");
            BigDecimal total = replied && !declined ? totalOf(theirs.values(), charge) : null;
            boolean complete = replied && !declined && lines.stream()
                    .allMatch(line -> theirs.containsKey(line.id()) && theirs.get(line.id()).price() != null);
            String status = declined ? "DECLINED" : replied ? "REPLIED" : row.get("opened_at") != null ? "OPENED"
                    : row.get("sent_at") != null ? "SENT" : "NOT_SENT";
            String url = url((String) row.get("token"));
            String vendorName = (String) row.get("vendor_name");
            String message = message(buyer, vendorName, rfq, lines, replyBy, url);
            String phone = WhatsAppNumbers.normalize((String) row.get("contact_phone"));
            suppliers.add(new Supplier(rowId, (UUID) row.get("vendor_id"), vendorName, (String) row.get("contact_phone"),
                    (String) row.get("email"), status, (String) row.get("sent_via"), offset(row.get("sent_at")),
                    offset(row.get("opened_at")), offset(row.get("replied_at")), (String) row.get("reply_source"),
                    ((Number) row.get("replies")).intValue() > 1, charge, date(row.get("can_deliver_on")),
                    (String) row.get("remark"), lines.stream().map(line -> theirs.get(line.id())).filter(Objects::nonNull).toList(),
                    total, complete, (UUID) row.get("order_id"), (String) row.get("po_number"), url, message,
                    phone == null ? null : "https://wa.me/" + phone + "?text="
                            + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20")));
        }
        Map<UUID, UUID> lowest = lowestByLine(lines, suppliers);
        return new Request(id, rfq, (String) request.get("status"), date(request.get("needed_by")), replyBy,
                (UUID) request.get("branch_id"), (String) request.get("note"),
                personName(request), offset(request.get("created_at")), lines, suppliers, lowest,
                options(lines, suppliers, lowest), repliesClosed(replyBy),
                "DONE".equals(request.get("status")) ? picked(id, lines, suppliers, lowest) : List.of());
    }

    // ------------------------------------------------------------------------------ sending ----

    /** The buyer opened WhatsApp with the message, or copied it: what we know, never "delivered". */
    @Transactional
    public Request markShared(UUID id, UUID supplierRowId, String channel) {
        requireOpen(id);
        jdbc.update("""
                UPDATE price_request_suppliers SET sent_via = :via, sent_at = now()
                WHERE id = :rowId AND request_id = :id
                """, new MapSqlParameterSource().addValue("via", "WHATSAPP".equals(channel) ? "WHATSAPP" : "MESSAGE")
                .addValue("rowId", supplierRowId).addValue("id", id));
        return get(id);
    }

    // ------------------------------------------------------------------------------ replies ----

    /** The buyer types what a supplier said on the phone ("Add Tony's prices"). */
    @Transactional
    public Request typeReply(UUID id, UUID supplierRowId, Reply reply, UUID userId) {
        requireMayOrder(userId);
        requireOpen(id);
        Map<String, Object> row = one("SELECT * FROM price_request_suppliers WHERE id = :rowId AND request_id = :id",
                new MapSqlParameterSource().addValue("rowId", supplierRowId).addValue("id", id));
        if (row == null) {
            throw new ExpectedDeliveryException(HttpStatus.NOT_FOUND, "That supplier isn't on this request.");
        }
        save(id, supplierRowId, reply, "TYPED");
        return get(id);
    }

    private boolean save(UUID requestId, UUID supplierRowId, Reply reply, String source) {
        List<Line> lines = lines(requestId);
        Set<UUID> lineIds = new java.util.HashSet<>();
        lines.forEach(line -> lineIds.add(line.id()));
        if (reply.deliveryCharge() != null && (reply.deliveryCharge().signum() < 0 || reply.deliveryCharge().compareTo(MAX_PRICE) > 0)) {
            throw bad("Type a delivery charge of 0 or more.");
        }
        String remark = blankToNull(reply.remark());
        if (remark != null && remark.length() > 500) {
            throw bad("Keep the note to 500 characters.");
        }
        Map<UUID, LineReply> byLine = new HashMap<>();
        for (LineReply line : reply.lines() == null ? List.<LineReply>of() : reply.lines()) {
            if (!lineIds.contains(line.lineId())) {
                throw bad("That isn't one of the items asked about.");
            }
            if (line.price() != null && (line.price().signum() < 0 || line.price().compareTo(MAX_PRICE) > 0)) {
                throw bad("Type each price as a number, like 45000.");
            }
            byLine.put(line.lineId(), line);
        }
        boolean declined = reply.declined() || (!lines.isEmpty() && lines.stream()
                .allMatch(line -> byLine.get(line.id()) != null && byLine.get(line.id()).cannotSupply()));
        if (!declined && lines.stream().noneMatch(line -> byLine.get(line.id()) != null && byLine.get(line.id()).price() != null)) {
            throw bad("Type a price for at least one item, or say you can't supply.");
        }
        Map<String, Object> before = one("SELECT replied_at FROM price_request_suppliers WHERE id = :rowId",
                new MapSqlParameterSource("rowId", supplierRowId));
        boolean changed = before != null && before.get("replied_at") != null;
        jdbc.update("DELETE FROM price_request_quotes WHERE supplier_row_id = :rowId", new MapSqlParameterSource("rowId", supplierRowId));
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            LineReply answer = byLine.get(line.id());
            if (answer == null || declined) {
                continue;
            }
            BigDecimal price = answer.cannotSupply() ? null : answer.price();
            jdbc.update("""
                    INSERT INTO price_request_quotes (supplier_row_id, line_id, price, cannot_supply)
                    VALUES (:rowId, :lineId, :price, :cannot)
                    """, new MapSqlParameterSource().addValue("rowId", supplierRowId).addValue("lineId", line.id())
                    .addValue("price", price).addValue("cannot", answer.cannotSupply()));
            if (price != null) {
                total = total.add(price.multiply(line.quantity()));
            }
        }
        if (!declined && reply.deliveryCharge() != null) {
            total = total.add(reply.deliveryCharge());
        }
        jdbc.update("""
                UPDATE price_request_suppliers SET replied_at = now(), reply_source = :source, delivery_charge = :charge,
                       can_deliver_on = :on, remark = :remark, declined = :declined
                WHERE id = :rowId
                """, new MapSqlParameterSource().addValue("source", source)
                .addValue("charge", declined ? null : reply.deliveryCharge())
                .addValue("on", reply.canDeliverOn() == null ? null : Date.valueOf(reply.canDeliverOn()))
                .addValue("remark", remark).addValue("declined", declined).addValue("rowId", supplierRowId));
        jdbc.update("""
                INSERT INTO price_request_reply_history (id, supplier_row_id, replied_at, reply_source, total, reply)
                VALUES (:id, :rowId, now(), :source, :total, :reply)
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("rowId", supplierRowId)
                .addValue("source", source).addValue("total", declined ? null : total.setScale(2, RoundingMode.HALF_UP))
                .addValue("reply", jsonMapper.writeValueAsString(reply)));
        jdbc.update("UPDATE price_requests SET updated_at = now() WHERE id = :id", new MapSqlParameterSource("id", requestId));
        return changed;
    }

    // ------------------------------------------------------------------------------ choosing ---

    /**
     * "Make 2 purchase orders": the supplier the buyer picked for each item (section 12.2, PICKED - it starts on the
     * cheapest, and any supplier who priced the item can be picked, or none for "Not now"), or the older two ways: the
     * cheapest for each item, or everything from one supplier. Each becomes an ordinary purchase order at the prices
     * they gave, with their delivery charge once - made as the person choosing, so it waits for approval when they
     * can only create orders.
     */
    @Transactional
    public ChooseResult choose(UUID id, String kind, UUID supplierRowId, List<Pick> picks, UUID userId) {
        requireMayOrder(userId);
        Request request = get(id);
        if (!"OPEN".equals(request.status())) {
            throw new ExpectedDeliveryException(HttpStatus.CONFLICT, "This request is already closed.");
        }
        Map<UUID, Supplier> byRow = new HashMap<>();
        request.suppliers().forEach(supplier -> byRow.put(supplier.id(), supplier));
        Map<UUID, List<Line>> linesBySupplier = new LinkedHashMap<>();
        if ("PICKED".equals(kind)) {
            Map<UUID, Line> lineById = new LinkedHashMap<>();
            request.lines().forEach(line -> lineById.put(line.id(), line));
            Set<UUID> seen = new java.util.HashSet<>();
            for (Pick pick : picks == null ? List.<Pick>of() : picks) {
                Line line = lineById.get(pick.lineId());
                if (line == null || !seen.add(pick.lineId())) {
                    throw bad("Each item is picked once, from this request.");
                }
                if (pick.supplierId() == null) {
                    continue;
                }
                Supplier chosen = byRow.get(pick.supplierId());
                if (chosen == null || !"REPLIED".equals(chosen.status()) || priceFrom(chosen, line.id()) == null) {
                    throw bad(line.productName() + " can only be ordered from a supplier who priced it.");
                }
                linesBySupplier.computeIfAbsent(chosen.id(), key -> new ArrayList<>()).add(line);
            }
            // Kept in the request's order, so each purchase order lists items as they were asked.
            linesBySupplier.replaceAll((supplierId, chosenLines) -> chosenLines.stream()
                    .sorted(Comparator.comparing(request.lines()::indexOf)).toList());
            if (linesBySupplier.isEmpty()) {
                throw bad("Pick a supplier for at least one item.");
            }
        } else if ("SUPPLIER".equals(kind)) {
            Supplier chosen = byRow.get(supplierRowId);
            if (chosen == null || !"REPLIED".equals(chosen.status())) {
                throw bad("Pick a supplier who sent prices.");
            }
            for (Line line : request.lines()) {
                if (priceFrom(chosen, line.id()) != null) {
                    linesBySupplier.computeIfAbsent(chosen.id(), key -> new ArrayList<>()).add(line);
                }
            }
        } else {
            for (Line line : request.lines()) {
                UUID lowest = request.lowestByLine().get(line.id());
                if (lowest != null) {
                    linesBySupplier.computeIfAbsent(lowest, key -> new ArrayList<>()).add(line);
                }
            }
        }
        if (linesBySupplier.isEmpty()) {
            throw bad("No supplier has priced anything yet.");
        }
        List<UUID> orderIds = new ArrayList<>();
        for (Map.Entry<UUID, List<Line>> entry : linesBySupplier.entrySet()) {
            Supplier supplier = byRow.get(entry.getKey());
            List<ExpectedDeliveryRequest.Line> orderLines = entry.getValue().stream()
                    .map(line -> new ExpectedDeliveryRequest.Line(line.productId(), line.unit(), line.quantity(),
                            priceFrom(supplier, line.id())))
                    .toList();
            ExpectedDeliveryResponse order = expectedDeliveryService.create(new ExpectedDeliveryRequest(
                    supplier.vendorId(), request.neededBy() == null ? null : request.neededBy().toString(), null,
                    request.note(), orderLines, request.branchId(), true, false, false, supplier.deliveryCharge(),
                    null), userId);
            jdbc.update("UPDATE expected_deliveries SET price_request_id = :requestId WHERE id = :orderId AND client_id = :clientId",
                    new MapSqlParameterSource().addValue("requestId", id).addValue("orderId", order.id())
                            .addValue("clientId", requireTenantId()));
            jdbc.update("UPDATE price_request_suppliers SET order_id = :orderId WHERE id = :rowId",
                    new MapSqlParameterSource().addValue("orderId", order.id()).addValue("rowId", supplier.id()));
            orderIds.add(order.id());
        }
        jdbc.update("UPDATE price_requests SET status = 'DONE', decided_at = now(), updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id));
        return new ChooseResult(orderIds);
    }

    @Transactional
    public Request close(UUID id, UUID userId) {
        requireMayOrder(userId);
        requireOpen(id);
        jdbc.update("UPDATE price_requests SET status = 'CLOSED', decided_at = now(), updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id));
        return get(id);
    }

    /**
     * "Give more time" (section 12.1): a new Reply by, today or later, while nothing is decided. Suppliers' links take
     * prices again until then; nothing is sent to them by itself.
     */
    @Transactional
    public Request giveMoreTime(UUID id, LocalDate replyBy, UUID userId) {
        requireMayOrder(userId);
        requireOpen(id);
        if (replyBy == null || replyBy.isBefore(today())) {
            throw bad("Pick a day from today on.");
        }
        jdbc.update("UPDATE price_requests SET reply_by = :replyBy, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("replyBy", Date.valueOf(replyBy)).addValue("id", id));
        return get(id);
    }

    // ---------------------------------------------------------------------------- public link --

    /** What the supplier's page shows. {@code state} OPEN, ORDERED (they were chosen) or CLOSED. */
    /** @param repliesClosed the Reply by day has passed: the page shows what they sent, and takes no more. */
    public record PublicView(String buyerName, String buyerPhone, String buyerLogoUrl, String rfqNumber,
            String supplierName, LocalDate neededBy, LocalDate replyBy, String deliverTo, List<PublicLine> lines,
            String state, String orderNumber, String orderUrl, PublicReply reply, boolean repliesClosed) {
    }

    public record PublicLine(UUID id, String productName, String comesIn, BigDecimal quantity) {
    }

    public record PublicReply(OffsetDateTime repliedAt, List<Quote> quotes, BigDecimal deliveryCharge,
            LocalDate canDeliverOn, String remark, boolean declined, BigDecimal total) {
    }

    /** Looked up by token alone (no tenant on a public path); answers 404 to anything else. */
    @Transactional(readOnly = true)
    public PublicView view(String token) {
        Map<String, Object> row = byToken(token);
        UUID requestId = (UUID) row.get("request_id");
        List<Line> lines = lines(requestId);
        Map<UUID, Line> lineById = new LinkedHashMap<>();
        lines.forEach(line -> lineById.put(line.id(), line));
        Map<UUID, Quote> theirs = quotes(requestId, lineById).getOrDefault((UUID) row.get("id"), Map.of());
        boolean replied = row.get("replied_at") != null;
        boolean declined = Boolean.TRUE.equals(row.get("declined"));
        BigDecimal charge = (BigDecimal) row.get("delivery_charge");
        String state = "OPEN".equals(row.get("status")) ? "OPEN" : row.get("order_id") != null ? "ORDERED" : "CLOSED";
        String orderToken = (String) row.get("share_token");
        String base = emailProperties.normalizedAppBaseUrl();
        return new PublicView((String) row.get("company_name"), (String) row.get("company_phone"),
                (String) row.get("logo_url"), (String) row.get("rfq_number"), (String) row.get("vendor_name"),
                date(row.get("needed_by")), date(row.get("reply_by")), (String) row.get("branch_name"),
                lines.stream().map(line -> new PublicLine(line.id(), line.productName(), line.comesIn(), line.quantity())).toList(),
                state, "ORDERED".equals(state) ? (String) row.get("po_number") : null,
                "ORDERED".equals(state) && orderToken != null && !base.isEmpty() ? base + "/o/" + orderToken : null,
                replied ? new PublicReply(offset(row.get("replied_at")), lines.stream().map(line -> theirs.get(line.id()))
                        .filter(Objects::nonNull).toList(), charge, date(row.get("can_deliver_on")),
                        (String) row.get("remark"), declined, declined ? null : totalOf(theirs.values(), charge)) : null,
                repliesClosed(date(row.get("reply_by"))));
    }

    /** The page's script ran on their phone: "Opened the link" (a WhatsApp preview never runs it). */
    @Transactional
    public void opened(String token) {
        Map<String, Object> row = byToken(token);
        jdbc.update("UPDATE price_request_suppliers SET opened_at = COALESCE(opened_at, now()) WHERE id = :id",
                new MapSqlParameterSource("id", row.get("id")));
    }

    /** The supplier sent (or changed) their prices from the link: saved, and the buyer is told. */
    @Transactional
    public PublicView reply(String token, Reply reply) {
        Map<String, Object> row = byToken(token);
        if (!"OPEN".equals(row.get("status"))) {
            throw new ExpectedDeliveryException(HttpStatus.CONFLICT, "This request is closed, so prices can't be changed.");
        }
        LocalDate replyBy = date(row.get("reply_by"));
        if (repliesClosed(replyBy)) {
            throw new ExpectedDeliveryException(HttpStatus.CONFLICT, "Replies closed on " + replyBy.format(DAY)
                    + ". To change your prices, call " + row.get("company_name") + ".");
        }
        UUID requestId = (UUID) row.get("request_id");
        UUID clientId = (UUID) row.get("client_id");
        boolean changed = save(requestId, (UUID) row.get("id"), reply, "LINK");
        PublicView view = view(token);
        tellBuyer(clientId, requestId, (UUID) row.get("created_by"), (String) row.get("vendor_name"),
                (String) row.get("rfq_number"), view.reply(), changed);
        return view;
    }

    /**
     * To whoever asked and everyone who can send orders: the bell and the phone. No email - three
     * suppliers would be three emails (owner pick 1, 2026-11-06).
     */
    private void tellBuyer(UUID clientId, UUID requestId, UUID askedBy, String vendorName, String rfq,
            PublicReply reply, boolean changed) {
        Set<UUID> people = new LinkedHashSet<>();
        if (askedBy != null) {
            people.add(askedBy);
        }
        people.addAll(userRepository.activeIdsWithPermission(clientId, ExpectedDeliveryService.SEND_PURCHASE_ORDER));
        if (people.isEmpty()) {
            return;
        }
        String title = reply != null && reply.declined()
                ? vendorName + " can't do " + rfq
                : vendorName + (changed ? " changed their prices" : " sent prices");
        String body = rfq + (reply != null && reply.total() != null ? " · " + PurchaseOrderSnapshot.naira(reply.total()) : "")
                + (reply != null && reply.remark() != null ? " · with a note" : "");
        String link = "/app/purchase-orders/rfq/" + requestId;
        for (UUID person : people) {
            notificationService.notifyClient(clientId, person, NotificationType.PRICE_REPLY, title, body, link, null);
        }
        pushService.notify(people, new PushMessage(title, body, "rfq-" + requestId, link, false));
    }

    // ------------------------------------------------------------------------------ helpers ----

    private Map<String, Object> byToken(String token) {
        if (token == null || token.length() < 16 || token.length() > 64) {
            throw notFoundLink();
        }
        Map<String, Object> row = one("""
                SELECT s.*, r.client_id, r.rfq_number, r.status, r.needed_by, r.reply_by, r.created_by,
                       v.name AS vendor_name, c.name AS company_name, c.phone AS company_phone, c.logo_url,
                       b.name AS branch_name, e.po_number, e.share_token
                FROM price_request_suppliers s
                JOIN price_requests r ON r.id = s.request_id
                JOIN company_vendors v ON v.id = s.vendor_id
                JOIN clients c ON c.id = r.client_id
                LEFT JOIN branches b ON b.id = r.branch_id
                LEFT JOIN expected_deliveries e ON e.id = s.order_id
                WHERE s.token = :token
                """, new MapSqlParameterSource("token", token));
        if (row == null) {
            throw notFoundLink();
        }
        return row;
    }

    private static ExpectedDeliveryException notFoundLink() {
        return new ExpectedDeliveryException(HttpStatus.NOT_FOUND, "This link isn't working. Ask the buyer to send it again.");
    }

    private List<Line> lines(UUID requestId) {
        return jdbc.query("""
                SELECT id, product_id, product_name, unit, comes_in, quantity FROM price_request_lines
                WHERE request_id = :id ORDER BY position
                """, new MapSqlParameterSource("id", requestId), (rs, i) -> new Line(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getBigDecimal(6).stripTrailingZeros()));
    }

    private Map<UUID, Map<UUID, Quote>> quotes(UUID requestId, Map<UUID, Line> lines) {
        Map<UUID, Map<UUID, Quote>> bySupplier = new HashMap<>();
        jdbc.query("""
                SELECT q.supplier_row_id, q.line_id, q.price, q.cannot_supply FROM price_request_quotes q
                JOIN price_request_suppliers s ON s.id = q.supplier_row_id WHERE s.request_id = :id
                """, new MapSqlParameterSource("id", requestId), rs -> {
            UUID lineId = rs.getObject(2, UUID.class);
            BigDecimal price = rs.getBigDecimal(3);
            Line line = lines.get(lineId);
            BigDecimal amount = price == null || line == null ? null
                    : price.multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP);
            bySupplier.computeIfAbsent(rs.getObject(1, UUID.class), key -> new HashMap<>())
                    .put(lineId, new Quote(lineId, price, rs.getBoolean(4), amount));
        });
        return bySupplier;
    }

    private static BigDecimal totalOf(java.util.Collection<Quote> quotes, BigDecimal charge) {
        BigDecimal total = quotes.stream().map(Quote::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return charge == null ? total : total.add(charge);
    }

    private static BigDecimal priceFrom(Supplier supplier, UUID lineId) {
        return supplier.quotes().stream().filter(quote -> quote.lineId().equals(lineId)).map(Quote::price)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static Map<UUID, UUID> lowestByLine(List<Line> lines, List<Supplier> suppliers) {
        Map<UUID, UUID> lowest = new HashMap<>();
        for (Line line : lines) {
            suppliers.stream()
                    .filter(supplier -> "REPLIED".equals(supplier.status()) && priceFrom(supplier, line.id()) != null)
                    .min(Comparator.comparing((Supplier supplier) -> priceFrom(supplier, line.id())))
                    .ifPresent(best -> lowest.put(line.id(), best.id()));
        }
        return lowest;
    }

    /** "Cheapest for each item" (when it splits the order) and "Everything from X", each with its true total. */
    private static List<Option> options(List<Line> lines, List<Supplier> suppliers, Map<UUID, UUID> lowest) {
        List<Option> options = new ArrayList<>();
        Map<UUID, Supplier> byId = new HashMap<>();
        suppliers.forEach(supplier -> byId.put(supplier.id(), supplier));
        Set<UUID> splitAcross = new LinkedHashSet<>(lowest.values());
        if (splitAcross.size() > 1) {
            BigDecimal total = BigDecimal.ZERO;
            for (Line line : lines) {
                UUID best = lowest.get(line.id());
                if (best != null) {
                    total = total.add(priceFrom(byId.get(best), line.id()).multiply(line.quantity()));
                }
            }
            for (UUID supplierId : splitAcross) {
                BigDecimal charge = byId.get(supplierId).deliveryCharge();
                if (charge != null) {
                    total = total.add(charge);
                }
            }
            options.add(new Option("CHEAPEST", null, total.setScale(2, RoundingMode.HALF_UP), splitAcross.size(),
                    splitAcross.stream().map(supplierId -> byId.get(supplierId).vendorName()).toList(), lowest.size()));
        }
        suppliers.stream()
                .filter(supplier -> "REPLIED".equals(supplier.status()) && supplier.total() != null)
                .sorted(Comparator.comparing(Supplier::total))
                .forEach(supplier -> options.add(new Option("SUPPLIER", supplier.id(), supplier.total(), 1,
                        List.of(supplier.vendorName()),
                        (int) lines.stream().filter(line -> priceFrom(supplier, line.id()) != null).count())));
        return options;
    }

    private void requireOpen(UUID id) {
        String status = jdbc.query("SELECT status FROM price_requests WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", id).addValue("clientId", requireTenantId()),
                rs -> rs.next() ? rs.getString(1) : null);
        if (status == null) {
            throw new ExpectedDeliveryException(HttpStatus.NOT_FOUND, "We couldn't find that price request.");
        }
        if (!"OPEN".equals(status)) {
            throw new ExpectedDeliveryException(HttpStatus.CONFLICT, "This request is already closed.");
        }
    }

    /** Price requests commit no money, so anyone who can create orders may send them (owner pick 3). */
    private void requireMayOrder(UUID userId) {
        if (userId != null && !permissions.has(userId, ExpectedDeliveryService.SEND_PURCHASE_ORDER)
                && !permissions.has(userId, ExpectedDeliveryService.CREATE_PURCHASE_ORDER)) {
            throw new AccessDeniedException("Your account can't ask suppliers for prices.");
        }
    }

    private Map<UUID, Map<String, StockInTemplateRow>> waysToBuy(List<UUID> productIds) {
        Map<UUID, Map<String, StockInTemplateRow>> ways = new HashMap<>();
        for (StockInTemplateRow row : stockInTemplateService.rows(productIds, null, null, null)) {
            ways.computeIfAbsent(row.productId(), key -> new LinkedHashMap<>()).put(UnitOptions.key(row.option()), row);
        }
        return ways;
    }

    /** In the buyer's voice, like the order's message: the items, the reply-by day, then the link. */
    static String message(String buyer, String supplier, String rfq, List<Line> lines, LocalDate replyBy, String url) {
        StringBuilder text = new StringBuilder();
        text.append("Hello ").append(supplier).append(", ").append(buyer).append(" would like your prices for ")
                .append(lines.size() == 1 ? "1 item" : lines.size() + " items").append(" (").append(rfq).append("):\n");
        for (Line line : lines) {
            text.append("• ").append(line.productName()).append(": ").append(line.quantity().toPlainString())
                    .append(" × ").append(line.comesIn()).append('\n');
        }
        text.append('\n');
        if (replyBy != null) {
            text.append("Please reply by ").append(replyBy.format(DAY)).append(". Prices can't be changed after that. ");
        }
        if (url != null) {
            text.append("Type your prices here, it takes a minute:\n").append(url);
        } else {
            text.append("Please reply with your prices.");
        }
        return text.toString();
    }

    private String url(String token) {
        String base = emailProperties.normalizedAppBaseUrl();
        return base.isEmpty() ? null : base + "/q/" + token;
    }

    private Map<String, Object> one(String sql, MapSqlParameterSource params) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String personName(Map<String, Object> row) {
        String first = row.get("first_name") == null ? "" : ((String) row.get("first_name")).trim();
        String last = row.get("last_name") == null ? "" : ((String) row.get("last_name")).trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? (String) row.get("username") : full;
    }

    private static OffsetDateTime offset(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime time) {
            return time;
        }
        if (value instanceof Timestamp stamp) {
            return stamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        return null;
    }

    private static LocalDate date(Object value) {
        if (value instanceof Date day) {
            return day.toLocalDate();
        }
        if (value instanceof LocalDate day) {
            return day;
        }
        return null;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static ExpectedDeliveryException bad(String message) {
        return new ExpectedDeliveryException(HttpStatus.BAD_REQUEST, message);
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }

    private static LocalDate today() {
        return LocalDate.now(LAGOS);
    }

    /** Replies close at the end of the Reply by day, where the business is (owner's pick, 2026-11-06). */
    static boolean repliesClosed(LocalDate replyBy) {
        return replyBy != null && today().isAfter(replyBy);
    }

    /**
     * What each item went to, from the orders the request became, beside the lowest offered - read back, not stored:
     * the orders hold the prices, and the replies hold the rest.
     */
    private List<Picked> picked(UUID requestId, List<Line> lines, List<Supplier> suppliers, Map<UUID, UUID> lowest) {
        Map<UUID, Supplier> byRow = new HashMap<>();
        suppliers.forEach(supplier -> byRow.put(supplier.id(), supplier));
        List<Picked> picked = new ArrayList<>();
        for (Line line : lines) {
            Supplier lowestSupplier = lowest.get(line.id()) == null ? null : byRow.get(lowest.get(line.id()));
            for (Supplier supplier : suppliers) {
                if (supplier.orderId() == null || priceFrom(supplier, line.id()) == null) {
                    continue;
                }
                Boolean onOrder = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM expected_delivery_lines WHERE expected_delivery_id = :orderId
                                         AND product_id = :productId)
                        """, new MapSqlParameterSource().addValue("orderId", supplier.orderId())
                        .addValue("productId", line.productId()), Boolean.class);
                if (Boolean.TRUE.equals(onOrder)) {
                    picked.add(new Picked(line.id(), supplier.id(), supplier.vendorName(), priceFrom(supplier, line.id()),
                            lowestSupplier == null ? null : priceFrom(lowestSupplier, line.id()),
                            lowestSupplier == null ? null : lowestSupplier.vendorName(), supplier.orderNumber()));
                }
            }
        }
        return picked;
    }
}
