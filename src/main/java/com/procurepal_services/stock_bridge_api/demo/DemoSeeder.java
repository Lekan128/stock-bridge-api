package com.procurepal_services.stock_bridge_api.demo;

import com.procurepal_services.stock_bridge_api.branch.AreaMoveService;
import com.procurepal_services.stock_bridge_api.branch.AreaService;
import com.procurepal_services.stock_bridge_api.branch.StockRequestService;
import com.procurepal_services.stock_bridge_api.branch.dto.StockRequestCreateRequest;
import com.procurepal_services.stock_bridge_api.expected.PriceRequestService;
import com.procurepal_services.stock_bridge_api.user.RoleManagementService;
import com.procurepal_services.stock_bridge_api.user.UserManagementService;
import com.procurepal_services.stock_bridge_api.user.dto.CreateRoleRequest;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.branch.BranchService;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.CompanyVendorService;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.demo.DemoCatalog.Home;
import com.procurepal_services.stock_bridge_api.demo.DemoCatalog.Item;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.expected.ExpectedDeliveryService;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryRequest;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import com.procurepal_services.stock_bridge_api.product.ProductManagementService;
import com.procurepal_services.stock_bridge_api.product.category.CompanyCategoryService;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.selling.SellingSettingsService;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.dto.StockCountRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import com.procurepal_services.stock_bridge_api.storage.S3ImageService;
import com.procurepal_services.stock_bridge_api.storage.UploadResult;
import com.procurepal_services.stock_bridge_api.transfer.TransferService;
import com.procurepal_services.stock_bridge_api.transfer.dto.ReceiptRequest;
import com.procurepal_services.stock_bridge_api.transfer.dto.TransferRequest;
import com.procurepal_services.stock_bridge_api.transfer.dto.TransferResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fills an empty demo company with a restaurant's last {@value #DAYS} days, ending now: two branches with
 * their areas, suppliers, {@link DemoCatalog#ITEMS} with pictures, and the history a busy kitchen leaves -
 * deliveries, the day's issues to the kitchen, bar sales, counts, spoilt stock, transfers between branches
 * and a few open purchase orders.
 *
 * <p>Everything goes through the same services the app's screens use, as the company's account holder, so
 * the figures add up exactly as they would for a real restaurant. Only the dates are set by hand where a
 * service has no "when it happened" to pass (when a product was added, when an order was made).
 *
 * <p>Runs inside {@link DemoResetService}'s transaction, as the demo company's tenant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoSeeder {

    static final int DAYS = 90;

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    static final String MAIN_BRANCH = "Lekki Phase 1";
    static final String SECOND_BRANCH = "Ikeja GRA";

    /** The second branch is smaller: this share of the main branch's trade. */
    private static final BigDecimal SECOND_BRANCH_SHARE = new BigDecimal("0.6");

    /** Kept in the main kitchen and counted every other evening; everything else is used as it is issued. */
    private static final Set<String> KEPT_IN_KITCHEN =
            Set.of("rice", "veg-oil", "palm-oil", "seasoning", "salt", "curry", "thyme", "crayfish", "egusi");

    /** Deliveries stop a few days before the end, so these sit under their level when a prospect looks. */
    private static final Set<String> LOW_AT_MAIN = Set.of("tatashe", "malt", "serviettes");
    private static final Set<String> LOW_AT_SECOND = Set.of("eggs", "cola", "gas", "stout");

    private static final List<String> SPOILT_NOTES = List.of(
            "Went soft over the weekend",
            "Freezer was off for some hours - power cut",
            "Too ripe to serve",
            "Delivered bad, supplier to replace",
            "Dropped during prep");

    private final BranchService branchService;
    private final AreaService areaService;
    private final AreaMoveService areaMoveService;
    private final CompanyVendorService companyVendorService;
    private final CompanyCategoryService companyCategoryService;
    private final ProductManagementService productManagementService;
    private final StockManagementService stockManagementService;
    private final TransferService transferService;
    private final ExpectedDeliveryService expectedDeliveryService;
    private final SellingSettingsService sellingSettingsService;
    private final S3ImageService s3ImageService;
    private final RoleManagementService roleManagementService;
    private final UserManagementService userManagementService;
    private final PriceRequestService priceRequestService;
    private final StockRequestService stockRequestService;
    private final NamedParameterJdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    /** Writes since the session was last emptied - see {@link #written}. */
    private int writesSinceClear;

    /**
     * Called after every stock write. The whole seed is one transaction, and every query flushes the session
     * first, checking each entity it holds for changes: left to grow, each write would be slower than the
     * last. Emptying it every few writes keeps them all as fast as the first. Safe because nothing here holds
     * an entity across writes - the services each load what they need.
     */
    private void written() {
        if (++writesSinceClear >= 15) {
            entityManager.flush();
            entityManager.clear();
            writesSinceClear = 0;
        }
    }

    /** What was made, for the SuperAdmin screen. */
    public record Result(int products, int suppliers, int pictures, long movements, int transfers, int purchaseOrders,
            int staff) {
    }

    /** One branch while the history is played. */
    private static final class BranchPlan {
        final UUID id;
        final String name;
        final BigDecimal share;
        final Map<Home, UUID> homeAreas = new HashMap<>();
        UUID kitchen;
        UUID bar;
        /** Who records this branch's day-to-day stock: its storekeeper. */
        UUID recorder;
        final Set<String> lowAtEnd;

        BranchPlan(UUID id, String name, BigDecimal share, Set<String> lowAtEnd) {
            this.id = id;
            this.name = name;
            this.share = share;
            this.lowAtEnd = lowAtEnd;
        }

        UUID areaFor(Home home) {
            return homeAreas.getOrDefault(home, homeAreas.get(Home.STORE));
        }

        boolean main() {
            return share.compareTo(BigDecimal.ONE) == 0;
        }
    }

    public Result seed(UUID clientId, UUID ownerId, Consumer<String> progress) {
        Random random = new Random(20261111L);
        OffsetDateTime now = OffsetDateTime.now(LAGOS);
        LocalDate firstDay = now.toLocalDate().minusDays(DAYS);
        OffsetDateTime start = firstDay.atTime(7, 0).atZone(LAGOS).toOffsetDateTime();

        progress.accept("Setting up branches and areas");
        branchService.setEnabledBySupport(clientId, true);
        // Support's switch, not the owner's: in the selling pilot a new company can't turn it on itself.
        sellingSettingsService.turnOnForDemo(clientId);

        UUID mainId = jdbc.queryForObject(
                "SELECT id FROM branches WHERE client_id = :clientId AND is_default", params(clientId), UUID.class);
        branchService.update(mainId, new BranchRequest(MAIN_BRANCH, "+234 800 000 0001", "12 Admiralty Way, Lekki Phase 1, Lagos"));
        UUID secondId = branchService.add(new BranchRequest(SECOND_BRANCH, "+234 800 000 0002", "5 Isaac John Street, Ikeja GRA, Lagos")).id();

        BranchPlan main = new BranchPlan(mainId, MAIN_BRANCH, BigDecimal.ONE, LOW_AT_MAIN);
        BranchPlan second = new BranchPlan(secondId, SECOND_BRANCH, SECOND_BRANCH_SHARE, LOW_AT_SECOND);
        setUpAreas(main, clientId, ownerId, true);
        setUpAreas(second, clientId, ownerId, false);
        List<BranchPlan> branches = List.of(main, second);

        progress.accept("Adding the kitchen staff");
        Staff staff = ensureStaff(clientId, ownerId, main, second);
        main.recorder = staff.mainStore();
        second.recorder = staff.secondStore();

        progress.accept("Adding suppliers and products");
        Map<String, UUID> categories = new HashMap<>();
        for (String name : DemoCatalog.CATEGORIES) {
            categories.put(name, companyCategoryService.create(name).id());
        }
        Map<String, UUID> suppliers = new LinkedHashMap<>();
        for (DemoCatalog.Supplier supplier : DemoCatalog.SUPPLIERS) {
            suppliers.put(supplier.key(), companyVendorService.create(new CompanyVendorRequest(
                    supplier.name(), supplier.phone(), supplier.email(), null, null, supplier.city(), "Lagos",
                    supplier.notes(), null, null, null, null)).id());
        }
        Map<String, UUID> products = new LinkedHashMap<>();
        int pictures = 0;
        for (Item item : DemoCatalog.ITEMS) {
            UUID productId = createProduct(item, categories, suppliers, ownerId);
            products.put(item.key(), productId);
            if (attachPicture(clientId, productId, item.key())) {
                pictures++;
            }
        }

        progress.accept("Playing back " + DAYS + " days of stock");
        Map<String, BigDecimal> stock = new HashMap<>();
        Map<String, BigDecimal> owed = new HashMap<>();
        List<UUID> transfersToReceive = new ArrayList<>();
        UUID pendingTransfer = null;
        int transfers = 0;

        for (int day = 0; day <= DAYS; day++) {
            LocalDate date = firstDay.plusDays(day);
            boolean lastDays = day > DAYS - 6;
            double busy = busyness(date.getDayOfWeek());

            // Yesterday's transfers arrive first thing.
            for (UUID transferId : transfersToReceive) {
                OffsetDateTime at = at(date, 8, 15, now);
                if (at != null) {
                    receiveTransfer(transferId, second, at, stock, ownerId);
                }
            }
            transfersToReceive.clear();

            for (BranchPlan branch : branches) {
                // Morning deliveries. The first day is everything coming in: the opening stock.
                for (Item item : DemoCatalog.ITEMS) {
                    int offset = Math.floorMod(item.key().hashCode(), item.deliverEvery());
                    boolean due = day == 0 || (day + offset) % item.deliverEvery() == 0;
                    boolean heldBack = lastDays && branch.lowAtEnd.contains(item.key());
                    if (!due || heldBack) {
                        continue;
                    }
                    OffsetDateTime at = at(date, 7, 30 + random.nextInt(60), now);
                    if (at == null) {
                        continue;
                    }
                    deliver(branch, item, products.get(item.key()), suppliers, day, at, stock, random, ownerId);
                }
            }

            for (BranchPlan branch : branches) {
                // The morning issue to the kitchen: today's fresh things and dry goods, used as they are issued;
                // on Mondays the main kitchen also takes its week of rice, oil and seasoning to keep.
                OffsetDateTime issueAt = at(date, 9, 30, now);
                if (issueAt != null) {
                    issueToKitchen(branch, date, busy, products, stock, owed, random, issueAt, ownerId);
                }

                // Spoilt stock, now and then, in the main branch's cold room.
                if (branch.main() && day % 5 == 3) {
                    OffsetDateTime at = at(date, 11, 0, now);
                    if (at != null) {
                        reportSpoilt(branch, products, stock, random, at, ownerId);
                    }
                }

                // The day's bar sales, as one sale at closing.
                OffsetDateTime closeAt = at(date, 22, 0, now);
                if (closeAt != null) {
                    sellAtBar(branch, day, busy, products, stock, random, closeAt, ownerId);
                }

                // The main kitchen counts what it keeps every other evening; what's gone is what was used.
                if (branch.main() && day % 2 == 1) {
                    OffsetDateTime at = at(date, 22, 30, now);
                    if (at != null) {
                        countKitchen(branch, products, stock, random, at, staff.chef());
                    }
                }

                // Sundays: the bar is counted.
                if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    OffsetDateTime at = at(date, 23, 0, now);
                    if (at != null) {
                        countBar(branch, products, stock, random, at, ownerId);
                    }
                }
            }

            // Every two weeks the main branch sends dry goods to the second; it arrives the next morning.
            // The last one is still on the way.
            if (day % 14 == 6 || day == DAYS - 1) {
                OffsetDateTime at = at(date, 15, 0, now);
                if (at != null) {
                    UUID transferId = sendTransfer(main, second, products, stock, at, ownerId);
                    if (transferId != null) {
                        transfers++;
                        if (day == DAYS - 1) {
                            pendingTransfer = transferId;
                        } else {
                            transfersToReceive.add(transferId);
                        }
                    }
                }
            }

            if (day % 10 == 0) {
                progress.accept("Playing back " + DAYS + " days of stock (" + date + ")");
                // Each day's writes are done; the next day's reads go to the database, not the session.
                entityManager.flush();
                entityManager.clear();
            }
        }
        log.debug("Demo transfer still on the way: {}", pendingTransfer);

        progress.accept("Leaving a few things low");
        leaveLow(branches, products, stock, now.minusHours(2), ownerId);

        progress.accept("Adding purchase orders");
        int purchaseOrders = createPurchaseOrders(main, second, products, suppliers, now, ownerId);

        progress.accept("Adding a price request, an approval and a kitchen request");
        purchaseOrders += addExamples(main, products, suppliers, staff, now, ownerId);

        progress.accept("Setting the dates");
        entityManager.flush();
        backdate(clientId, start);

        long movements = jdbc.queryForObject(
                "SELECT count(*) FROM stock_movements WHERE client_id = :clientId", params(clientId), Long.class);
        return new Result(products.size(), suppliers.size(), pictures, movements, transfers, purchaseOrders, 3);
    }

    // ------------------------------------------------------------------------------------------- set-up

    private void setUpAreas(BranchPlan branch, UUID clientId, UUID ownerId, boolean coldRoom) {
        // The Store is made by a trigger when the branch row is written, so the branch must be written first.
        entityManager.flush();
        UUID store = jdbc.queryForObject("""
                SELECT id FROM areas WHERE client_id = :clientId AND branch_id = :branchId AND is_original
                """, params(clientId).addValue("branchId", branch.id), UUID.class);
        branch.homeAreas.put(Home.STORE, store);

        List<AreaRequest.NewArea> wanted = new ArrayList<>();
        if (coldRoom) {
            wanted.add(new AreaRequest.NewArea("Cold room", true, false, false));
        }
        wanted.add(new AreaRequest.NewArea("Kitchen", true, true, false));
        wanted.add(new AreaRequest.NewArea("Bar", true, false, false));
        for (AreaResponse area : areaService.add(branch.id, new AreaRequest(wanted), ownerId)) {
            switch (area.name()) {
                case "Cold room" -> branch.homeAreas.put(Home.COLD_ROOM, area.id());
                case "Kitchen" -> branch.kitchen = area.id();
                case "Bar" -> {
                    branch.bar = area.id();
                    branch.homeAreas.put(Home.BAR, area.id());
                }
                default -> {
                }
            }
        }
    }

    private UUID createProduct(Item item, Map<String, UUID> categories, Map<String, UUID> suppliers, UUID ownerId) {
        List<CreateProductRequest.SupplierLink> links = new ArrayList<>();
        links.add(new CreateProductRequest.SupplierLink(
                suppliers.get(item.supplierKey()), null, item.packCost(), item.packUnit(), item.packSize()));
        if (item.key().equals("rice")) {
            // A second supplier, a little cheaper: the product page shows both and who was cheaper.
            links.add(new CreateProductRequest.SupplierLink(
                    suppliers.get("daleko-rice"), null, new BigDecimal("92000"), item.packUnit(), item.packSize()));
        }
        CreateProductRequest request = new CreateProductRequest(
                item.name(),
                "DM-" + item.key().toUpperCase().replace('-', '_'),
                item.description(),
                item.sellPrice(),
                item.lowStock(),
                item.stockUnit(),
                item.packUnit(),
                item.packSize(),
                null,
                categories.get(item.category()),
                null,
                null,
                item.measures().isEmpty() ? null : item.measures(),
                item.usedIn(),
                null,
                links,
                null);
        return productManagementService.create(request, null, ownerId).id();
    }

    /** The product's picture from {@code demo/images/<key>.jpg}, if there is one and pictures can be stored. */
    private boolean attachPicture(UUID clientId, UUID productId, String key) {
        if (!s3ImageService.isConfigured()) {
            return false;
        }
        ClassPathResource resource = new ClassPathResource("demo/images/" + key + ".jpg");
        if (!resource.exists()) {
            return false;
        }
        byte[] content;
        try (InputStream in = resource.getInputStream()) {
            content = in.readAllBytes();
        } catch (IOException e) {
            log.warn("Could not read demo picture {}: {}", key, e.getMessage());
            return false;
        }
        UploadResult uploaded = s3ImageService.uploadDemoImage(clientId, key + ".jpg", content, "image/jpeg");
        if (!uploaded.success()) {
            log.warn("Could not store demo picture {}: {}", key, uploaded.failureReason());
            return false;
        }
        jdbc.update("UPDATE products SET image_url = :url WHERE id = :id AND client_id = :clientId",
                params(clientId).addValue("url", uploaded.url()).addValue("id", productId));
        return true;
    }

    // ------------------------------------------------------------------------------------------ history

    private void deliver(BranchPlan branch, Item item, UUID productId, Map<String, UUID> suppliers, int day,
            OffsetDateTime at, Map<String, BigDecimal> stock, Random random, UUID ownerId) {
        UUID area = branch.areaFor(item.home());
        String key = key(branch.id, area, item.key());
        BigDecimal daily = item.dailyUse().multiply(branch.share);
        BigDecimal target = daily.multiply(BigDecimal.valueOf(item.deliverEvery() + 3L));
        BigDecimal have = stock.getOrDefault(key, BigDecimal.ZERO);
        BigDecimal short_ = target.subtract(have);
        if (short_.signum() <= 0) {
            return;
        }

        String unit;
        BigDecimal quantity;
        BigDecimal stockUnits;
        BigDecimal price;
        // Prices drift a little from delivery to delivery, the way market prices do.
        BigDecimal drift = BigDecimal.valueOf(0.95 + random.nextDouble() * 0.12);
        if (item.packUnit() != null) {
            unit = item.packUnit();
            quantity = short_.divide(item.packSize(), 0, RoundingMode.CEILING).max(BigDecimal.ONE);
            stockUnits = quantity.multiply(item.packSize());
            price = roundPrice(item.packCost().multiply(drift));
        } else {
            unit = item.stockUnit();
            boolean whole = "PIECE".equals(item.stockUnit());
            quantity = whole ? short_.setScale(0, RoundingMode.CEILING) : roundHalf(short_);
            stockUnits = quantity;
            price = roundPrice(item.packCost().multiply(drift));
        }

        String supplierKey = item.supplierKey();
        if (item.key().equals("rice") && day % 2 == 1) {
            supplierKey = "daleko-rice";
            price = roundPrice(new BigDecimal("92000").multiply(drift));
        }
        String note = day == 0 ? "Opening stock" : null;
        stockManagementService.stockIn(productId, new StockInRequest(
                quantity, price, note, unit, suppliers.get(supplierKey), null, null, at, null, null, null,
                branch.id, area), branch.recorder);
        written();
        stock.merge(key, stockUnits, BigDecimal::add);
    }

    private void issueToKitchen(BranchPlan branch, LocalDate date, double busy, Map<String, UUID> products,
            Map<String, BigDecimal> stock, Map<String, BigDecimal> owed, Random random, OffsetDateTime at,
            UUID ownerId) {
        Map<UUID, List<AreaMoveRequest.Line>> byArea = new LinkedHashMap<>();
        boolean monday = date.getDayOfWeek() == DayOfWeek.MONDAY;
        // Fresh things go to the kitchen every morning; dry goods twice a week, enough for the days between.
        boolean dryGoodsDay = monday || date.getDayOfWeek() == DayOfWeek.THURSDAY;
        for (Item item : DemoCatalog.ITEMS) {
            if (item.home() == Home.BAR) {
                continue;
            }
            boolean kept = branch.main() && KEPT_IN_KITCHEN.contains(item.key());
            UUID from = branch.areaFor(item.home());
            String fromKey = key(branch.id, from, item.key());
            BigDecimal available = stock.getOrDefault(fromKey, BigDecimal.ZERO);

            BigDecimal wanted;
            if (kept) {
                if (!monday && stock.getOrDefault(key(branch.id, branch.kitchen, item.key()), BigDecimal.ZERO).signum() > 0) {
                    continue;
                }
                wanted = item.dailyUse().multiply(BigDecimal.valueOf(7));
            } else {
                double use = item.dailyUse().multiply(branch.share).doubleValue() * busy * (0.85 + random.nextDouble() * 0.3);
                BigDecimal due = owed.getOrDefault(key(branch.id, null, item.key()), BigDecimal.ZERO)
                        .add(BigDecimal.valueOf(use));
                if (!item.perishable() && !dryGoodsDay) {
                    owed.put(key(branch.id, null, item.key()), due);
                    continue;
                }
                wanted = due.setScale(0, RoundingMode.FLOOR);
                owed.put(key(branch.id, null, item.key()), due.subtract(wanted));
            }
            BigDecimal quantity = wanted.min(available).setScale(0, RoundingMode.FLOOR);
            if (quantity.signum() <= 0) {
                continue;
            }
            byArea.computeIfAbsent(from, ignored -> new ArrayList<>())
                    .add(new AreaMoveRequest.Line(products.get(item.key()), quantity, item.stockUnit(), !kept));
            stock.merge(fromKey, quantity.negate(), BigDecimal::add);
            if (kept) {
                stock.merge(key(branch.id, branch.kitchen, item.key()), quantity, BigDecimal::add);
            }
        }
        for (Map.Entry<UUID, List<AreaMoveRequest.Line>> move : byArea.entrySet()) {
            areaMoveService.move(new AreaMoveRequest(
                    UUID.randomUUID(), branch.id, move.getKey(), branch.kitchen, at, false,
                    "Morning issue", move.getValue()), branch.recorder);
            written();
        }
    }

    private void countKitchen(BranchPlan branch, Map<String, UUID> products, Map<String, BigDecimal> stock,
            Random random, OffsetDateTime at, UUID chefId) {
        for (Item item : DemoCatalog.ITEMS) {
            if (!KEPT_IN_KITCHEN.contains(item.key())) {
                continue;
            }
            String key = key(branch.id, branch.kitchen, item.key());
            BigDecimal have = stock.getOrDefault(key, BigDecimal.ZERO);
            if (have.signum() <= 0) {
                continue;
            }
            BigDecimal used = item.dailyUse().multiply(BigDecimal.valueOf(2 * (0.85 + random.nextDouble() * 0.3)));
            BigDecimal counted = roundHalf(have.subtract(used).max(BigDecimal.ZERO));
            stockManagementService.count(products.get(item.key()),
                    new StockCountRequest(counted, at, null, branch.id, branch.kitchen), chefId);
            written();
            stock.put(key, counted);
        }
    }

    private void sellAtBar(BranchPlan branch, int day, double busy, Map<String, UUID> products,
            Map<String, BigDecimal> stock, Random random, OffsetDateTime at, UUID ownerId) {
        UUID saleId = UUID.randomUUID();
        for (Item item : DemoCatalog.ITEMS) {
            if (!item.sold()) {
                continue;
            }
            String key = key(branch.id, branch.bar, item.key());
            BigDecimal have = stock.getOrDefault(key, BigDecimal.ZERO);
            // Slow sellers are rung up every other night, two days' worth.
            boolean slow = item.dailyUse().compareTo(BigDecimal.valueOf(15)) < 0;
            if (slow && day % 2 == 1) {
                continue;
            }
            double days = slow ? 2 : 1;
            double wanted = item.dailyUse().multiply(branch.share).doubleValue() * days * busy * (0.75 + random.nextDouble() * 0.5);
            BigDecimal quantity = BigDecimal.valueOf(Math.round(wanted)).min(have);
            if (quantity.signum() <= 0) {
                continue;
            }
            stockManagementService.stockOut(products.get(item.key()), new StockOutRequest(
                    quantity, item.sellPrice(), null, item.stockUnit(), null, at, null, OutReason.SALE, saleId,
                    null, branch.id, branch.bar), branch.recorder);
            written();
            stock.merge(key, quantity.negate(), BigDecimal::add);
        }
    }

    private void countBar(BranchPlan branch, Map<String, UUID> products, Map<String, BigDecimal> stock,
            Random random, OffsetDateTime at, UUID ownerId) {
        for (Item item : DemoCatalog.ITEMS) {
            if (!item.sold()) {
                continue;
            }
            String key = key(branch.id, branch.bar, item.key());
            BigDecimal have = stock.getOrDefault(key, BigDecimal.ZERO);
            // Now and then a bottle or two has gone missing.
            BigDecimal missing = random.nextInt(5) == 0 ? BigDecimal.valueOf(1 + random.nextInt(2)) : BigDecimal.ZERO;
            BigDecimal counted = have.subtract(missing).max(BigDecimal.ZERO);
            stockManagementService.count(products.get(item.key()),
                    new StockCountRequest(counted, at, missing.signum() > 0 ? "Short at the Sunday count" : null,
                            branch.id, branch.bar), branch.recorder);
            written();
            stock.put(key, counted);
        }
    }

    private void reportSpoilt(BranchPlan branch, Map<String, UUID> products, Map<String, BigDecimal> stock,
            Random random, OffsetDateTime at, UUID ownerId) {
        List<Item> perishable = DemoCatalog.ITEMS.stream().filter(item -> item.home() == Home.COLD_ROOM).toList();
        Item item = perishable.get(random.nextInt(perishable.size()));
        UUID area = branch.areaFor(item.home());
        String key = key(branch.id, area, item.key());
        BigDecimal have = stock.getOrDefault(key, BigDecimal.ZERO);
        BigDecimal quantity = BigDecimal.valueOf(1 + random.nextInt(2)).min(have.setScale(0, RoundingMode.FLOOR));
        if (quantity.signum() <= 0) {
            return;
        }
        OutReason reason = random.nextBoolean() ? OutReason.DAMAGED : OutReason.EXPIRED;
        stockManagementService.stockOut(products.get(item.key()), new StockOutRequest(
                quantity, null, SPOILT_NOTES.get(random.nextInt(SPOILT_NOTES.size())), item.stockUnit(), null, at,
                null, reason, null, null, branch.id, area), branch.recorder);
        written();
        stock.merge(key, quantity.negate(), BigDecimal::add);
    }

    /** The sample kitchen staff: made once, kept by every reset like any other user. */
    record Staff(UUID chef, UUID mainStore, UUID secondStore) {
    }

    static final String CHEF_ROLE = "Head chef";

    /**
     * Three people the history is recorded by, so "who did this" shows names and a prospect sees roles in
     * use. Made the first time, by the account holder, with passwords nobody knows - they exist to be seen,
     * not to sign in. A later reset finds them and only puts them back at their branch.
     *
     * <p>The head chef's role is custom: it may make purchase orders but not send them, so what the chef
     * orders waits for the owner's approval - the approval example below.
     */
    private Staff ensureStaff(UUID clientId, UUID ownerId, BranchPlan main, BranchPlan second) {
        UUID chefRole = jdbc.query("SELECT id FROM roles WHERE client_id = :clientId AND name = :name",
                params(clientId).addValue("name", CHEF_ROLE), (rs, n) -> rs.getObject("id", UUID.class))
                .stream().findFirst().orElse(null);
        if (chefRole == null) {
            java.util.Set<String> codes = new java.util.HashSet<>(jdbc.queryForList("""
                    SELECT p.code FROM roles r JOIN role_permissions rp ON rp.role_id = r.id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE r.client_id IS NULL AND r.name = 'STOREKEEPER'
                    """, new MapSqlParameterSource(), String.class));
            codes.remove("SEND_PURCHASE_ORDER");
            codes.add("CREATE_PURCHASE_ORDER");
            chefRole = roleManagementService.create(new CreateRoleRequest(CHEF_ROLE,
                    "Runs the kitchen. Makes purchase orders that wait for approval before they go.", codes)).id();
        }
        UUID storekeeper = jdbc.queryForObject(
                "SELECT id FROM roles WHERE client_id IS NULL AND name = 'STOREKEEPER'", new MapSqlParameterSource(), UUID.class);
        UUID chef = ensureUser(clientId, ownerId, "bisi.adeyemi", "Bisi", "Adeyemi", "Head chef", chefRole, main.id);
        UUID mainStore = ensureUser(clientId, ownerId, "emeka.okafor", "Emeka", "Okafor", "Storekeeper", storekeeper, main.id);
        UUID secondStore = ensureUser(clientId, ownerId, "halima.musa", "Halima", "Musa", "Storekeeper", storekeeper, second.id);
        entityManager.flush();
        return new Staff(chef, mainStore, secondStore);
    }

    private UUID ensureUser(UUID clientId, UUID ownerId, String username, String firstName, String lastName,
            String jobTitle, UUID roleId, UUID branchId) {
        UUID existing = jdbc.query("SELECT id FROM users WHERE client_id = :clientId AND username = :username",
                params(clientId).addValue("username", username), (rs, n) -> rs.getObject("id", UUID.class))
                .stream().findFirst().orElse(null);
        if (existing != null) {
            branchService.assign(existing, List.of(branchId));
            return existing;
        }
        // Random and never shown (bcrypt reads at most 72 bytes; this is 64).
        String password = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return userManagementService.create(new CreateUserRequest(username, password, roleId, firstName, lastName,
                null, null, jobTitle, List.of(branchId), null), ownerId).id();
    }

    /**
     * One of each thing that is otherwise empty in a fresh company: an order the chef made that waits for
     * the owner's approval, a price request two suppliers have answered (ready to compare and order from),
     * and the kitchen asking the store for today's stock.
     */
    private int addExamples(BranchPlan main, Map<String, UUID> products, Map<String, UUID> suppliers, Staff staff,
            OffsetDateTime now, UUID ownerId) {
        LocalDate today = now.toLocalDate();
        expectedDeliveryService.create(new ExpectedDeliveryRequest(
                suppliers.get("coldchain-foods"), today.plusDays(2).toString(), null, "For the weekend",
                List.of(line(products, "chicken", "CARTON", "4", "55000"), line(products, "turkey", "CARTON", "2", "70000")),
                main.id, true, false, null, null, "Saturday is fully booked - we'll run out of chicken."), staff.chef());

        PriceRequestService.Request asked = priceRequestService.create(new PriceRequestService.NewRequest(
                List.of(rfqLine(products, "rice", "5"), rfqLine(products, "veg-oil", "3"), rfqLine(products, "tomato-paste", "4")),
                List.of(suppliers.get("bodija-provisions"), suppliers.get("daleko-rice")),
                today.plusDays(6), today.plusDays(2), main.id, "Prices for next month's dry goods"), ownerId);
        Map<UUID, String> lineKeys = new HashMap<>();
        for (PriceRequestService.Line line : asked.lines()) {
            products.forEach((key, id) -> {
                if (id.equals(line.productId())) {
                    lineKeys.put(line.id(), key);
                }
            });
        }
        for (PriceRequestService.Supplier supplier : asked.suppliers()) {
            boolean daleko = supplier.vendorId().equals(suppliers.get("daleko-rice"));
            List<PriceRequestService.LineReply> replies = new ArrayList<>();
            for (PriceRequestService.Line line : asked.lines()) {
                String key = lineKeys.get(line.id());
                if (daleko && key.equals("tomato-paste")) {
                    replies.add(new PriceRequestService.LineReply(line.id(), null, true));
                    continue;
                }
                BigDecimal price = item(key).packCost();
                if (daleko) {
                    // Cheaper rice, dearer oil: the request page shows the cheapest mix.
                    price = key.equals("rice") ? new BigDecimal("91000") : price.add(new BigDecimal("3000"));
                }
                replies.add(new PriceRequestService.LineReply(line.id(), price, false));
            }
            priceRequestService.typeReply(asked.id(), supplier.id(), new PriceRequestService.Reply(replies,
                    daleko ? new BigDecimal("8000") : new BigDecimal("5000"), today.plusDays(daleko ? 4 : 2),
                    daleko ? "No tomato paste this month" : null, false), ownerId);
        }

        stockRequestService.ask(new StockRequestCreateRequest(UUID.randomUUID(), main.id, main.homeAreas.get(Home.STORE),
                main.kitchen, now.minusMinutes(40), "For lunch service", List.of(
                        new StockRequestCreateRequest.Line(products.get("onions"), new BigDecimal("6"), "KG"),
                        new StockRequestCreateRequest.Line(products.get("plantain"), new BigDecimal("30"), "PIECE"),
                        new StockRequestCreateRequest.Line(products.get("eggs"), new BigDecimal("30"), "PIECE"))),
                staff.chef());
        return 1;
    }

    private static PriceRequestService.NewLine rfqLine(Map<String, UUID> products, String key, String quantity) {
        Item item = item(key);
        return new PriceRequestService.NewLine(products.get(key),
                item.packUnit() + ":" + item.packSize().stripTrailingZeros().toPlainString(), new BigDecimal(quantity));
    }

    /**
     * A few things end under their reorder level at each branch, so Low stock, the dashboard and reorder
     * have something to say: the last of them was used this morning.
     */
    private void leaveLow(List<BranchPlan> branches, Map<String, UUID> products, Map<String, BigDecimal> stock,
            OffsetDateTime at, UUID ownerId) {
        for (BranchPlan branch : branches) {
            for (String itemKey : branch.lowAtEnd) {
                Item item = item(itemKey);
                UUID area = branch.areaFor(item.home());
                String key = key(branch.id, area, itemKey);
                BigDecimal have = stock.getOrDefault(key, BigDecimal.ZERO);
                BigDecimal target = BigDecimal.valueOf(item.lowStock() / 2);
                BigDecimal used = have.subtract(target).setScale(0, RoundingMode.FLOOR);
                if (used.signum() <= 0) {
                    continue;
                }
                OutReason reason = item.sold() ? OutReason.SALE : OutReason.USED;
                stockManagementService.stockOut(products.get(itemKey), new StockOutRequest(
                        used, item.sold() ? item.sellPrice() : null, item.sold() ? "Event order" : null,
                        item.stockUnit(), null, at, null, reason,
                        item.sold() ? UUID.randomUUID() : null, null, branch.id, area), branch.recorder);
                written();
                stock.merge(key, used.negate(), BigDecimal::add);
            }
        }
    }

    private UUID sendTransfer(BranchPlan from, BranchPlan to, Map<String, UUID> products, Map<String, BigDecimal> stock,
            OffsetDateTime at, UUID ownerId) {
        List<TransferRequest.Line> lines = new ArrayList<>();
        Map<String, BigDecimal> moved = new HashMap<>();
        for (String itemKey : List.of("rice", "veg-oil", "tomato-paste", "spaghetti")) {
            Item item = item(itemKey);
            String key = key(from.id, from.areaFor(item.home()), itemKey);
            BigDecimal have = stock.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal quantity = item.packSize();
            if (have.compareTo(quantity.multiply(BigDecimal.valueOf(2))) < 0) {
                continue;
            }
            lines.add(new TransferRequest.Line(products.get(itemKey), BigDecimal.ONE, item.packUnit()));
            moved.put(itemKey, quantity);
        }
        if (lines.isEmpty()) {
            return null;
        }
        TransferResponse sent = transferService.send(new TransferRequest(
                UUID.randomUUID(), from.id, to.id, at, "Musa (driver)", "+234 800 000 0009",
                "Restock for " + to.name, lines, false), ownerId);
        written();
        moved.forEach((itemKey, quantity) -> {
            stock.merge(key(from.id, from.areaFor(item(itemKey).home()), itemKey), quantity.negate(), BigDecimal::add);
            stock.merge("transit|" + itemKey, quantity, BigDecimal::add);
        });
        return sent.id();
    }

    private void receiveTransfer(UUID transferId, BranchPlan to, OffsetDateTime at, Map<String, BigDecimal> stock,
            UUID ownerId) {
        transferService.receive(transferId, new ReceiptRequest(UUID.randomUUID(), at, true, null), ownerId);
        written();
        for (String itemKey : List.of("rice", "veg-oil", "tomato-paste", "spaghetti")) {
            BigDecimal quantity = stock.remove("transit|" + itemKey);
            if (quantity != null) {
                stock.merge(key(to.id, to.areaFor(Home.STORE), itemKey), quantity, BigDecimal::add);
            }
        }
    }

    private int createPurchaseOrders(BranchPlan main, BranchPlan second, Map<String, UUID> products,
            Map<String, UUID> suppliers, OffsetDateTime now, UUID ownerId) {
        LocalDate today = now.toLocalDate();
        // To send: the vegetables for tomorrow, made this morning.
        ExpectedDeliveryResponse fresh = expectedDeliveryService.create(order(
                suppliers.get("mile12-fresh"), today.plusDays(1), main.id, "Tomorrow's vegetables",
                line(products, "tomatoes", "BASKET", "2", "40000"),
                line(products, "tatashe", "BASKET", "1", "25000"),
                line(products, "onions", "BAG", "1", "70000")), ownerId);
        // Sent on WhatsApp, due in three days.
        ExpectedDeliveryResponse dry = expectedDeliveryService.create(order(
                suppliers.get("bodija-provisions"), today.plusDays(3), main.id, "Monthly dry goods",
                line(products, "rice", "BAG", "4", "95000"),
                line(products, "beans", "BAG", "2", "85000"),
                line(products, "veg-oil", "KEG", "3", "75000"),
                line(products, "tomato-paste", "CARTON", "4", "36000")), ownerId);
        expectedDeliveryService.markShared(dry.id(), ownerId, ExpectedDeliveryService.ShareChannel.WHATSAPP);
        // Sent, and late: due two days ago and not here yet.
        ExpectedDeliveryResponse drinks = expectedDeliveryService.create(order(
                suppliers.get("eko-drinks"), today.plusDays(1), second.id, "Drinks for the weekend",
                line(products, "cola", "PACK", "10", "6000"),
                line(products, "stout", "CRATE", "4", "14400"),
                line(products, "malt", "PACK", "4", "14400")), ownerId);
        expectedDeliveryService.markShared(drinks.id(), ownerId, ExpectedDeliveryService.ShareChannel.WHATSAPP);

        entityManager.flush();
        jdbc.update("UPDATE expected_deliveries SET created_at = :at WHERE id = :id",
                new MapSqlParameterSource("at", now.minusHours(2)).addValue("id", fresh.id()));
        jdbc.update("UPDATE expected_deliveries SET created_at = :at WHERE id = :id",
                new MapSqlParameterSource("at", now.minusDays(1)).addValue("id", dry.id()));
        jdbc.update("UPDATE expected_deliveries SET created_at = :at, expected_date = :due WHERE id = :id",
                new MapSqlParameterSource("at", now.minusDays(5))
                        .addValue("due", java.sql.Date.valueOf(today.minusDays(2)))
                        .addValue("id", drinks.id()));
        return 3;
    }

    private static ExpectedDeliveryRequest order(UUID supplierId, LocalDate due, UUID branchId, String note,
            ExpectedDeliveryRequest.Line... lines) {
        return new ExpectedDeliveryRequest(supplierId, due.toString(), null, note, List.of(lines), branchId, true,
                false, null, null, null);
    }

    private static ExpectedDeliveryRequest.Line line(Map<String, UUID> products, String key, String unit,
            String quantity, String price) {
        // A pack is named with its size, the way the order form offers it: "BASKET:25".
        Item item = item(key);
        String unitKey = unit.equals(item.packUnit())
                ? unit + ":" + item.packSize().stripTrailingZeros().toPlainString()
                : unit;
        return new ExpectedDeliveryRequest.Line(products.get(key), unitKey, new BigDecimal(quantity), new BigDecimal(price));
    }

    /**
     * The services stamp "now" on what they create; this puts the set-up back on the first day and the
     * record of each stock write on when it happened, so the screens read like ninety days of use.
     */
    private void backdate(UUID clientId, OffsetDateTime start) {
        MapSqlParameterSource params = params(clientId).addValue("start", start);
        for (String table : List.of("branches", "areas", "company_categories", "company_vendors", "products",
                "product_vendors")) {
            if (hasColumn(table, "created_at")) {
                jdbc.update("UPDATE " + table + " SET created_at = :start WHERE client_id = :clientId", params);
            }
        }
        for (String table : List.of("stock_movements", "area_moves", "stock_transfers")) {
            if (hasColumn(table, "created_at") && hasColumn(table, "occurred_at")) {
                jdbc.update("UPDATE " + table + " SET created_at = occurred_at WHERE client_id = :clientId", params);
            }
        }
    }

    private boolean hasColumn(String table, String column) {
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = :table AND column_name = :column
                """, new MapSqlParameterSource("table", table).addValue("column", column), Integer.class);
        return found != null && found > 0;
    }

    // -------------------------------------------------------------------------------------------- helpers

    /** A time on {@code date} in Lagos, or null when it is still to come. */
    private static OffsetDateTime at(LocalDate date, int hour, int minute, OffsetDateTime now) {
        OffsetDateTime at = date.atTime(LocalTime.of(hour, 0)).plusMinutes(minute).atZone(LAGOS).toOffsetDateTime();
        return at.isBefore(now.minusMinutes(5)) ? at : null;
    }

    /** Fridays and Saturdays are busy, Mondays quiet. */
    private static double busyness(DayOfWeek day) {
        return switch (day) {
            case FRIDAY, SATURDAY -> 1.3;
            case SUNDAY -> 1.1;
            case MONDAY -> 0.8;
            default -> 1.0;
        };
    }

    private static Item item(String key) {
        return DemoCatalog.ITEMS.stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow();
    }

    private static String key(UUID branchId, UUID areaId, String itemKey) {
        return branchId + "|" + areaId + "|" + itemKey;
    }

    private static BigDecimal roundHalf(BigDecimal value) {
        return value.multiply(BigDecimal.valueOf(2)).setScale(0, RoundingMode.CEILING).divide(BigDecimal.valueOf(2));
    }

    /** To the nearest ₦50, the way market prices are said. */
    private static BigDecimal roundPrice(BigDecimal value) {
        return value.divide(BigDecimal.valueOf(50), 0, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(50));
    }

    private static MapSqlParameterSource params(UUID clientId) {
        return new MapSqlParameterSource("clientId", clientId);
    }
}
