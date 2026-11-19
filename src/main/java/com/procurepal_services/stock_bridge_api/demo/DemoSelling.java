package com.procurepal_services.stock_bridge_api.demo;

import com.procurepal_services.stock_bridge_api.branch.AreaService;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaUpdateRequest;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.product.MadeWithService;
import com.procurepal_services.stock_bridge_api.product.ProductManagementService;
import com.procurepal_services.stock_bridge_api.product.category.CompanyCategoryService;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.selling.BillService;
import com.procurepal_services.stock_bridge_api.selling.CashDayService;
import com.procurepal_services.stock_bridge_api.selling.ChargeService;
import com.procurepal_services.stock_bridge_api.selling.PaymentAccountService;
import com.procurepal_services.stock_bridge_api.selling.SaleCancelReason;
import com.procurepal_services.stock_bridge_api.selling.SaleCancelService;
import com.procurepal_services.stock_bridge_api.selling.SalePaymentMethod;
import com.procurepal_services.stock_bridge_api.selling.TableService;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The demo's selling (owner, 2026-11-19: every selling feature testable on a fresh reset): a menu of dishes made here
 * with recipes and sides, the main branch's tables with kitchen and bar tickets, VAT and a service charge, transfer
 * accounts, two weeks of table service, closed drawers, a cancelled sale and one waiting for approval, and the
 * open tables a prospect finds right now - each in a different state.
 *
 * <p>Like the rest of {@link DemoSeeder}, everything goes through the app's own services, as the people who'd do
 * it; only the times are set by hand afterwards, where a service stamps "now".
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DemoSelling {

    /** One area set of a branch, as the seed made it. */
    record Places(UUID branch, UUID store, UUID kitchen, UUID bar) {}

    /** What {@link #setUp} made, for the history and today. */
    record Setup(Map<String, UUID> menu, List<UUID> tables, Map<String, UUID> tablesByName, Map<UUID, List<UUID>> accounts) {}

    record Ingredient(String item, String perOne) {}

    record Dish(String key, String name, String category, String price, String cost, String madeIn,
            List<Ingredient> recipe, Kind kind) {}

    enum Kind { MEAL, SIDE, DRINK }

    /** The menu: what the Kitchen and the Bar make. Recipes take only what the kitchen keeps, so its counts stay true. */
    static final List<Dish> MENU = List.of(
            new Dish("jollof", "Jollof rice & chicken", "Rice dishes", "7000", "2800", "Kitchen",
                    List.of(new Ingredient("rice", "0.2"), new Ingredient("veg-oil", "0.03")), Kind.MEAL),
            new Dish("fried-rice", "Fried rice & turkey", "Rice dishes", "7500", "3200", "Kitchen",
                    List.of(new Ingredient("rice", "0.2"), new Ingredient("veg-oil", "0.04")), Kind.MEAL),
            new Dish("egusi", "Egusi soup & pounded yam", "Soups", "6500", "2600", "Kitchen",
                    List.of(new Ingredient("egusi", "0.08"), new Ingredient("palm-oil", "0.04"), new Ingredient("crayfish", "0.02")),
                    Kind.MEAL),
            new Dish("pepper-soup", "Goat meat pepper soup", "Soups", "6000", "2500", "Kitchen", List.of(), Kind.MEAL),
            new Dish("small-chops", "Small chops", "Starters", "4500", "1800", "Kitchen",
                    List.of(new Ingredient("veg-oil", "0.05")), Kind.MEAL),
            new Dish("plantain-side", "Fried plantain", "Sides", "700", "250", "Kitchen", List.of(), Kind.SIDE),
            new Dish("coleslaw", "Coleslaw", "Sides", "500", "200", "Kitchen", List.of(), Kind.SIDE),
            new Dish("extra-chicken", "Extra chicken", "Sides", "2000", "900", "Kitchen", List.of(), Kind.SIDE),
            new Dish("chapman", "Chapman", "Drinks we make", "2500", "700", "Bar", List.of(), Kind.DRINK));

    /** Drinks kept at the bar, sold on a table's bill alongside the Chapman. */
    private static final List<String> BAR_DRINKS = List.of("lager", "cola", "water", "malt", "stout");

    private static final List<String> NOTES = List.of("No pepper", "Extra pepper", "Well done", "Bring it with the drinks");

    private final ProductManagementService productManagementService;
    private final ProductRepository productRepository;
    private final MadeWithService madeWithService;
    private final CompanyCategoryService companyCategoryService;
    private final AreaService areaService;
    private final TableService tableService;
    private final ChargeService chargeService;
    private final PaymentAccountService paymentAccountService;
    private final BillService billService;
    private final CashDayService cashDayService;
    private final SaleCancelService saleCancelService;
    private final StockManagementService stockManagementService;
    private final NamedParameterJdbcTemplate jdbc;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    // ------------------------------------------------------------------------------------------- set-up

    /** Before the history: the menu, the stores that only hold stock, tables and tickets, charges and accounts. */
    Setup setUp(UUID clientId, UUID ownerId, UUID waiterId, Places main, Places second, Map<String, UUID> stockProducts) {
        Map<String, UUID> categories = new LinkedHashMap<>();
        for (Dish dish : MENU) {
            categories.computeIfAbsent(dish.category(), name -> companyCategoryService.create(name).id());
        }
        companyCategoryService.setGoesWithMeal(categories.get("Sides"), true);

        Map<String, UUID> menu = new LinkedHashMap<>();
        for (Dish dish : MENU) {
            UUID id = productManagementService.create(new CreateProductRequest(dish.name(), "DM-MENU-" + dish.key().toUpperCase().replace('-', '_'),
                    null, new BigDecimal(dish.price()), null, "PIECE", null, null, null, categories.get(dish.category()), null, null,
                    null, null, null, null, null, true, new BigDecimal(dish.cost())).forAllBranches(), null, ownerId).id();
            List<MadeWithService.IngredientInput> recipe = dish.recipe().stream()
                    .map(ingredient -> new MadeWithService.IngredientInput(stockProducts.get(ingredient.item()),
                            new BigDecimal(ingredient.perOne()), null))
                    .toList();
            madeWithService.set(productRepository.findById(id).orElseThrow(),
                    new MadeWithService.MadeWithRequest(dish.madeIn(), null, recipe));
            menu.put(dish.key(), id);
        }

        // The Stores only hold stock: what's sold is moved to the Kitchen or the Bar first.
        for (Places places : List.of(main, second)) {
            areaService.update(places.store(), new AreaUpdateRequest(null, null, null, null, null, false), ownerId);
        }

        // Tables 1 to 10 (4 is Window) and VIP, at the main branch; its Kitchen and Bar work from tickets.
        tableService.add(clientId, main.branch(), new TableService.AddTablesRequest(10, null));
        for (TableService.Table table : tableService.list(clientId, main.branch())) {
            if (table.name().equals("4")) {
                tableService.update(clientId, table.id(), new TableService.TableRequest("Window", null));
            }
        }
        tableService.add(clientId, main.branch(), new TableService.AddTablesRequest(null, "VIP"));
        tableService.setTickets(clientId, main.branch(), true);
        Map<String, UUID> tablesByName = new LinkedHashMap<>();
        List<UUID> tables = new ArrayList<>();
        for (TableService.Table table : tableService.list(clientId, main.branch())) {
            tablesByName.put(table.name(), table.id());
            tables.add(table.id());
        }

        // VAT on top of every sale at both branches; a 10% service charge on the main branch's table bills.
        for (Places places : List.of(main, second)) {
            ChargeService.Charge vat = chargeService.list(clientId, places.branch()).getFirst();
            chargeService.update(clientId, vat.id(), new ChargeService.ChargeRequest(null, null, null, false, "EVERY_SALE", true));
        }
        chargeService.add(clientId, main.branch(),
                new ChargeService.ChargeRequest("Service charge", "PERCENT", BigDecimal.TEN, false, "TABLE_BILLS", true));

        // Two accounts per branch for transfers, made-up numbers.
        Map<UUID, List<UUID>> accounts = new LinkedHashMap<>();
        accounts.put(main.branch(), List.of(
                paymentAccountService.add(clientId, main.branch(),
                        new PaymentAccountService.AccountRequest("GTBank", "Lekki Kitchen Ltd", "0000012345", true)).id(),
                paymentAccountService.add(clientId, main.branch(),
                        new PaymentAccountService.AccountRequest("Moniepoint", "Lekki Kitchen Ltd", "0000067890", true)).id()));
        accounts.put(second.branch(), List.of(
                paymentAccountService.add(clientId, second.branch(),
                        new PaymentAccountService.AccountRequest("Access Bank", "Ikeja Kitchen Ltd", "0000024680", true)).id(),
                paymentAccountService.add(clientId, second.branch(),
                        new PaymentAccountService.AccountRequest("Opay", "Ikeja Kitchen Ltd", "0000013579", true)).id()));

        // The sample waiter starts in quick mode, on Tables, Tickets and Sell.
        jdbc.update("UPDATE users SET starts_in_quick = TRUE, quick_mode_tabs = 'tables,tickets,sell' WHERE id = :id",
                new MapSqlParameterSource("id", waiterId));
        return new Setup(menu, tables, tablesByName, accounts);
    }

    // ------------------------------------------------------------------------------------------- the history

    /**
     * A day of table service at the main branch: lunch and dinner bills, each a few meals (some with a side, now
     * and then a note), drinks from the Bar, sent, made, served and paid - mostly one way, some split. Stock leaves
     * the Kitchen's recipes and the Bar as it would; {@code stock} follows it so the evening counts stay true.
     */
    int serveDay(UUID clientId, Places main, Setup setup, LocalDate date, double busy, Map<String, UUID> stockProducts,
            Map<String, BigDecimal> stock, Random random, OffsetDateTime now, UUID waiterId, UUID chefId, int dayIndex) {
        int bills = (int) Math.round(6 * busy);
        int made = 0;
        for (int i = 0; i < bills; i++) {
            int minutes = 12 * 60 + 15 + i * (9 * 60 / Math.max(1, bills)) + random.nextInt(25);
            OffsetDateTime openedAt = date.atStartOfDay(DemoSeeder.LAGOS).toOffsetDateTime().plusMinutes(minutes);
            // Only bills long since paid: today's open tables come at the end.
            if (openedAt.isAfter(now.minusHours(2))) {
                continue;
            }
            UUID table = setup.tables().get(random.nextInt(setup.tables().size()));
            UUID billId = billService.open(clientId, waiterId, new BillService.OpenRequest(main.branch(), table, null)).id();
            OffsetDateTime sentAt = openedAt.plusMinutes(2);
            List<UUID> drinkLines = order(clientId, main, setup, billId, stockProducts, stock, random, sentAt, waiterId,
                    1 + random.nextInt(4), random.nextInt(4), true);
            // Now and then a drink comes off before it's opened: back to the Bar.
            if (!drinkLines.isEmpty() && random.nextInt(100) < 6) {
                UUID line = drinkLines.get(random.nextInt(drinkLines.size()));
                billService.takeOff(clientId, waiterId, billId, line, new BillService.TakeOffRequest("NOT_MADE", "Changed their mind"));
                putBack(main, line, stock);
            }
            pay(clientId, main, setup, billId, random, waiterId);
            OffsetDateTime paidAt = sentAt.plusMinutes(55 + random.nextInt(25));
            times(billId, main, openedAt, sentAt, paidAt, chefId, waiterId, true);
            made++;
        }
        return made;
    }

    /**
     * Sends a bill's order as one Send: {@code meals} dishes (a third with a side, some with a note) and
     * {@code drinks} drinks. Returns the drinks' lines, for taking one off. {@code fromCount}: the evening counts
     * follow the stock map, so it's kept in step.
     */
    private List<UUID> order(UUID clientId, Places main, Setup setup, UUID billId, Map<String, UUID> stockProducts,
            Map<String, BigDecimal> stock, Random random, OffsetDateTime at, UUID waiterId, int meals, int drinks, boolean fromCount) {
        UUID sendKey = UUID.randomUUID();
        List<Dish> mains = MENU.stream().filter(dish -> dish.kind() == Kind.MEAL).toList();
        List<Dish> sides = MENU.stream().filter(dish -> dish.kind() == Kind.SIDE).toList();
        for (int m = 0; m < meals; m++) {
            Dish dish = mains.get(random.nextInt(mains.size()));
            UUID mealKey = UUID.randomUUID();
            String note = random.nextInt(100) < 15 ? NOTES.get(random.nextInt(NOTES.size())) : null;
            sendDish(clientId, main, setup, billId, dish, mealKey, null, note, sendKey, at, waiterId, stockProducts, stock, fromCount);
            if (random.nextInt(100) < 35) {
                Dish side = sides.get(random.nextInt(sides.size()));
                sendDish(clientId, main, setup, billId, side, UUID.randomUUID(), mealKey, null, sendKey, at, waiterId, stockProducts,
                        stock, fromCount);
            }
        }
        List<UUID> drinkLines = new ArrayList<>();
        for (int d = 0; d < drinks; d++) {
            int quantity = 1 + random.nextInt(2);
            if (random.nextInt(100) < 25) {
                Dish chapman = MENU.stream().filter(dish -> dish.kind() == Kind.DRINK).findFirst().orElseThrow();
                UUID line = send(clientId, main.branch(), main.bar(), billId, setup.menu().get(chapman.key()),
                        BigDecimal.valueOf(quantity), new BigDecimal(chapman.price()), null, null, sendKey, at, waiterId);
                if (line != null) drinkLines.add(line);
                continue;
            }
            DemoCatalog.Item item = item(BAR_DRINKS.get(random.nextInt(BAR_DRINKS.size())));
            String key = DemoSeeder.key(main.branch(), main.bar(), item.key());
            BigDecimal have = stock.getOrDefault(key, BigDecimal.ZERO);
            if (fromCount && have.compareTo(BigDecimal.valueOf(quantity)) < 0) {
                continue;
            }
            UUID line = send(clientId, main.branch(), main.bar(), billId, stockProducts.get(item.key()), BigDecimal.valueOf(quantity),
                    item.sellPrice(), null, null, sendKey, at, waiterId);
            if (line != null) {
                drinkLines.add(line);
                if (fromCount) stock.merge(key, BigDecimal.valueOf(quantity).negate(), BigDecimal::add);
            }
        }
        return drinkLines;
    }

    private UUID sendDish(UUID clientId, Places main, Setup setup, UUID billId, Dish dish, UUID lineKey, UUID withKey, String note,
            UUID sendKey, OffsetDateTime at, UUID waiterId, Map<String, UUID> stockProducts, Map<String, BigDecimal> stock,
            boolean fromCount) {
        UUID line = send(clientId, main.branch(), main.kitchen(), billId, setup.menu().get(dish.key()), BigDecimal.ONE,
                new BigDecimal(dish.price()), lineKey, withKey, sendKey, at, waiterId, note);
        if (line != null && fromCount) {
            for (Ingredient ingredient : dish.recipe()) {
                stock.merge(DemoSeeder.key(main.branch(), main.kitchen(), ingredient.item()), new BigDecimal(ingredient.perOne()).negate(),
                        BigDecimal::add);
            }
        }
        return line;
    }

    private UUID send(UUID clientId, UUID branchId, UUID areaId, UUID billId, UUID productId, BigDecimal quantity, BigDecimal price,
            UUID lineKey, UUID withKey, UUID sendKey, OffsetDateTime at, UUID waiterId) {
        return send(clientId, branchId, areaId, billId, productId, quantity, price, lineKey, withKey, sendKey, at, waiterId, null);
    }

    /** One line sent to a bill, as the waiter's phone sends it. Null when the area hasn't enough (it's left off). */
    private UUID send(UUID clientId, UUID branchId, UUID areaId, UUID billId, UUID productId, BigDecimal quantity, BigDecimal price,
            UUID lineKey, UUID withKey, UUID sendKey, OffsetDateTime at, UUID waiterId, String note) {
        try {
            StockMutationResponse sent = stockManagementService.stockOut(productId, new StockOutRequest(quantity, price, null, null, null,
                    at, null, OutReason.SALE, billId, null, branchId, areaId, null, null,
                    new StockOutRequest.BillLine(lineKey == null ? UUID.randomUUID() : lineKey, withKey, note, sendKey)), waiterId);
            return sent.movement() == null ? null : sent.movement().id();
        } catch (com.procurepal_services.stock_bridge_api.stock.InsufficientStockException none) {
            return null;
        }
    }

    private void putBack(Places main, UUID movementId, Map<String, BigDecimal> stock) {
        jdbc.query("""
                SELECT p.id, o.quantity, p.sku FROM stock_movements o JOIN products p ON p.id = o.product_id WHERE o.id = :id AND NOT p.made_here
                """, new MapSqlParameterSource("id", movementId), rs -> {
                    String sku = rs.getString("sku");
                    String itemKey = sku.substring("DM-".length()).toLowerCase().replace('_', '-');
                    stock.merge(DemoSeeder.key(main.branch(), main.bar(), itemKey), rs.getBigDecimal("quantity"), BigDecimal::add);
                });
    }

    /** Paid at the Bar's till: mostly one way; about one bill in eight split two or three ways. */
    private void pay(UUID clientId, Places main, Setup setup, UUID billId, Random random, UUID waiterId) {
        BigDecimal toPay = billService.get(clientId, waiterId, billId).summary().toPay();
        if (toPay == null || toPay.signum() <= 0) {
            billService.close(clientId, waiterId, billId);
            return;
        }
        List<UUID> accounts = setup.accounts().get(main.branch());
        if (random.nextInt(100) < 12) {
            int parts = 2 + random.nextInt(2);
            BigDecimal each = toPay.divide(BigDecimal.valueOf(parts), 0, RoundingMode.FLOOR);
            for (int part = 0; part < parts; part++) {
                SalePaymentMethod method = SalePaymentMethod.values()[part % 3];
                billService.pay(clientId, waiterId, billId, new BillService.PayRequest(method,
                        method == SalePaymentMethod.TRANSFER ? accounts.get(part % accounts.size()) : null, main.bar(),
                        part == parts - 1 ? null : each));
            }
            return;
        }
        int roll = random.nextInt(10);
        SalePaymentMethod method = roll < 4 ? SalePaymentMethod.CASH : roll < 8 ? SalePaymentMethod.TRANSFER : SalePaymentMethod.CARD;
        billService.pay(clientId, waiterId, billId, new BillService.PayRequest(method,
                method == SalePaymentMethod.TRANSFER ? accounts.get(random.nextInt(accounts.size())) : null, main.bar(), null));
    }

    /**
     * The bill's times as they went (the services stamp "now"): opened, sent, the Bar's drinks ready in minutes and
     * the Kitchen's food in about twenty, each served a few minutes later, and the parts paid. {@code done}: made,
     * served and the "don't send"s seen; otherwise left as the tickets now stand.
     */
    private void times(UUID billId, Places main, OffsetDateTime openedAt, OffsetDateTime sentAt, OffsetDateTime paidAt, UUID chefId,
            UUID waiterId, boolean done) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("bill", billId).addValue("opened", openedAt)
                .addValue("sent", sentAt).addValue("paid", paidAt).addValue("bar", main.bar()).addValue("chef", chefId)
                .addValue("waiter", waiterId);
        jdbc.update("UPDATE bills SET opened_at = :opened, closed_at = CASE WHEN closed_at IS NULL THEN NULL ELSE CAST(:paid AS timestamptz) END WHERE id = :bill", params);
        jdbc.update("""
                UPDATE bill_lines SET sent_at = :sent,
                       taken_off_at = CASE WHEN taken_off IS NULL THEN NULL ELSE CAST(:sent AS timestamptz) + interval '10 minutes' END
                WHERE bill_id = :bill
                """, params);
        jdbc.update("""
                UPDATE stock_movements SET occurred_at = CAST(:sent AS timestamptz) + interval '10 minutes'
                WHERE reverses_movement_id IN (SELECT movement_id FROM bill_lines WHERE bill_id = :bill)
                """, params);
        jdbc.update("""
                UPDATE stock_movements SET occurred_at = :sent WHERE id IN (SELECT movement_id FROM bill_lines WHERE bill_id = :bill)
                """, params);
        if (done) {
            jdbc.update("""
                    UPDATE tickets SET created_at = :sent,
                           ready_at = CAST(:sent AS timestamptz) + CASE WHEN area_id = :bar THEN interval '4 minutes' ELSE interval '22 minutes' END,
                           ready_by = :chef,
                           served_at = CAST(:sent AS timestamptz) + CASE WHEN area_id = :bar THEN interval '7 minutes' ELSE interval '26 minutes' END,
                           served_by = :waiter
                    WHERE bill_id = :bill
                    """, params);
            jdbc.update("""
                    UPDATE ticket_lines l SET cleared_at = CAST(:sent AS timestamptz) + interval '12 minutes'
                    FROM tickets t WHERE t.id = l.ticket_id AND t.bill_id = :bill
                    """, params);
        } else {
            jdbc.update("UPDATE tickets SET created_at = :sent WHERE bill_id = :bill", params);
        }
        if (paidAt == null) {
            return;
        }
        jdbc.update("""
                UPDATE bill_payments p SET paid_at = CAST(:paid AS timestamptz) + (x.n - 1) * interval '2 minutes'
                FROM (SELECT id, row_number() OVER (ORDER BY paid_at, id) AS n FROM bill_payments WHERE bill_id = :bill) x
                WHERE p.id = x.id
                """, params);
        jdbc.update("UPDATE sales SET created_at = :sent WHERE id = :bill", params);
    }

    // ------------------------------------------------------------------------------------------- after the history

    /**
     * After the history's dates are set: a counter sale cancelled by the owner two days ago, and yesterday's sale the
     * waiter asked to cancel, waiting for a manager. Before the drawers are counted, so neither reopens one.
     */
    void cancellations(UUID clientId, Places main, LocalDate today, UUID ownerId, UUID waiterId) {
        List<UUID> twoDaysAgo = jdbc.queryForList("""
                SELECT o.sale_id FROM stock_movements o
                WHERE o.client_id = :clientId AND o.branch_id = :branch AND o.area_id = :bar AND o.movement_type = 'OUT'
                  AND o.sale_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM bills b WHERE b.id = o.sale_id)
                  AND (o.occurred_at AT TIME ZONE 'Africa/Lagos')::date = :day
                ORDER BY o.occurred_at LIMIT 1
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("branch", main.branch())
                .addValue("bar", main.bar()).addValue("day", today.minusDays(2)), UUID.class);
        for (UUID sale : twoDaysAgo) {
            saleCancelService.cancel(sale, SaleCancelReason.WRONG_AMOUNT, "Rung up twice", ownerId);
        }

        // The waiter's own sale yesterday evening, asked about long after its first two minutes.
        UUID saleId = UUID.randomUUID();
        OffsetDateTime at = today.minusDays(1).atTime(18, 40).atZone(DemoSeeder.LAGOS).toOffsetDateTime();
        DemoCatalog.Item water = item("water");
        UUID waterId = jdbc.queryForObject("SELECT id FROM products WHERE client_id = :clientId AND sku = 'DM-WATER'",
                new MapSqlParameterSource("clientId", clientId), UUID.class);
        try {
            stockManagementService.stockOut(waterId, new StockOutRequest(BigDecimal.valueOf(2), water.sellPrice(), null, null, null, at,
                    null, OutReason.SALE, saleId, null, main.branch(), main.bar(), SalePaymentMethod.CASH, null), waiterId);
        } catch (com.procurepal_services.stock_bridge_api.stock.InsufficientStockException none) {
            return;
        }
        jdbc.update("UPDATE stock_movements SET created_at = occurred_at WHERE sale_id = :sale", new MapSqlParameterSource("sale", saleId));
        jdbc.update("UPDATE sales SET created_at = :at WHERE id = :sale", new MapSqlParameterSource("sale", saleId).addValue("at", at));
        // The sale is read again with its real time, or it would still be in its first two minutes and simply cancel.
        entityManager.flush();
        entityManager.clear();
        saleCancelService.request(saleId, SaleCancelReason.CHANGED_MIND, "The guest left before it came", waiterId);
    }

    /**
     * Close the day at the main branch's Bar for the last two weeks: opened at 10 with ₦20,000, counted at half past
     * eleven, worked out like any close. One day's diesel came out of the drawer; one day was ₦1,000 short. Today's
     * drawer is open. Then the switch goes on, as if the owner had turned it on two weeks ago.
     */
    void drawers(UUID clientId, Places main, LocalDate today, OffsetDateTime now, UUID storekeeperId) {
        BigDecimal float20 = new BigDecimal("20000");
        for (int back = 14; back >= 1; back--) {
            LocalDate date = today.minusDays(back);
            OffsetDateTime opened = date.atTime(10, 0).atZone(DemoSeeder.LAGOS).toOffsetDateTime();
            OffsetDateTime closed = date.atTime(23, 30).atZone(DemoSeeder.LAGOS).toOffsetDateTime();
            List<CashDayService.PastMoneyOut> out = back == 5
                    ? List.of(new CashDayService.PastMoneyOut(new BigDecimal("8000"), CashDayService.OutKind.COST,
                            "Diesel for the generator", date.atTime(15, 0).atZone(DemoSeeder.LAGOS).toOffsetDateTime()))
                    : List.of();
            BigDecimal off = back == 3 ? new BigDecimal("-1000") : BigDecimal.ZERO;
            cashDayService.recordDay(clientId, main.branch(), main.bar(), opened, float20, out, closed, off, storekeeperId, storekeeperId);
        }
        OffsetDateTime openedToday = today.atTime(10, 0).atZone(DemoSeeder.LAGOS).toOffsetDateTime();
        if (openedToday.isAfter(now)) {
            openedToday = now.minusMinutes(30);
        }
        cashDayService.recordDay(clientId, main.branch(), main.bar(), openedToday, float20, List.of(), null, null, storekeeperId, null);
        jdbc.update("UPDATE clients SET counts_cash = TRUE WHERE id = :id", new MapSqlParameterSource("id", clientId));
    }

    /**
     * The tables a prospect finds right now, one of each: Table 2 being made, with a "Don't send" on its Kitchen
     * ticket; Table 5 with the Kitchen's food ready to take; Table 7 all served; VIP served and half paid; and a
     * takeaway bill for a name, waiting in the Kitchen.
     */
    void openTables(UUID clientId, Places main, Setup setup, OffsetDateTime now, UUID waiterId, UUID chefId) {
        Map<String, UUID> menu = setup.menu();
        Map<String, UUID> tables = setup.tablesByName();

        UUID two = open(clientId, main, tables.get("2"), null, waiterId);
        OffsetDateTime twoAt = now.minusMinutes(12);
        UUID sendTwo = UUID.randomUUID();
        send(clientId, main.branch(), main.kitchen(), two, menu.get("jollof"), BigDecimal.ONE, price("jollof"), null, null, sendTwo, twoAt, waiterId);
        UUID friedRice = send(clientId, main.branch(), main.kitchen(), two, menu.get("fried-rice"), BigDecimal.ONE, price("fried-rice"),
                null, null, sendTwo, twoAt, waiterId);
        send(clientId, main.branch(), main.kitchen(), two, menu.get("small-chops"), BigDecimal.ONE, price("small-chops"), null, null,
                sendTwo, twoAt, waiterId);
        drink(clientId, main, two, "cola", 2, sendTwo, twoAt, waiterId);
        times(two, main, now.minusMinutes(14), twoAt, null, chefId, waiterId, false);
        if (friedRice != null) {
            billService.takeOff(clientId, waiterId, two, friedRice, new BillService.TakeOffRequest("NOT_MADE", "Changed their mind"));
            jdbc.update("UPDATE bill_lines SET taken_off_at = :at WHERE movement_id = :id",
                    new MapSqlParameterSource("id", friedRice).addValue("at", now.minusMinutes(4)));
        }

        UUID five = open(clientId, main, tables.get("5"), null, waiterId);
        OffsetDateTime fiveAt = now.minusMinutes(35);
        UUID sendFive = UUID.randomUUID();
        UUID meal = UUID.randomUUID();
        send(clientId, main.branch(), main.kitchen(), five, menu.get("jollof"), BigDecimal.ONE, price("jollof"), meal, null, sendFive, fiveAt,
                waiterId, "No pepper");
        send(clientId, main.branch(), main.kitchen(), five, menu.get("plantain-side"), BigDecimal.ONE, price("plantain-side"), null, meal,
                sendFive, fiveAt, waiterId);
        send(clientId, main.branch(), main.kitchen(), five, menu.get("egusi"), BigDecimal.ONE, price("egusi"), null, null, sendFive, fiveAt,
                waiterId);
        drink(clientId, main, five, "lager", 2, sendFive, fiveAt, waiterId);
        times(five, main, now.minusMinutes(37), fiveAt, null, chefId, waiterId, false);
        ticketState(five, main, false, now.minusMinutes(3), null, chefId, waiterId);
        ticketState(five, main, true, now.minusMinutes(31), now.minusMinutes(28), chefId, waiterId);

        UUID seven = open(clientId, main, tables.get("7"), null, waiterId);
        OffsetDateTime sevenAt = now.minusMinutes(50);
        UUID sendSeven = UUID.randomUUID();
        send(clientId, main.branch(), main.kitchen(), seven, menu.get("pepper-soup"), BigDecimal.ONE, price("pepper-soup"), null, null,
                sendSeven, sevenAt, waiterId);
        send(clientId, main.branch(), main.kitchen(), seven, menu.get("fried-rice"), BigDecimal.ONE, price("fried-rice"), null, null,
                sendSeven, sevenAt, waiterId);
        send(clientId, main.branch(), main.bar(), seven, menu.get("chapman"), BigDecimal.valueOf(2), price("chapman"), null, null,
                sendSeven, sevenAt, waiterId);
        times(seven, main, now.minusMinutes(52), sevenAt, null, chefId, waiterId, false);
        ticketState(seven, main, false, now.minusMinutes(30), now.minusMinutes(27), chefId, waiterId);
        ticketState(seven, main, true, now.minusMinutes(46), now.minusMinutes(44), chefId, waiterId);

        UUID vip = open(clientId, main, tables.get("VIP"), null, waiterId);
        OffsetDateTime vipAt = now.minusMinutes(80);
        UUID sendVip = UUID.randomUUID();
        for (String dish : List.of("jollof", "fried-rice", "egusi", "small-chops")) {
            send(clientId, main.branch(), main.kitchen(), vip, menu.get(dish), BigDecimal.ONE, price(dish), null, null, sendVip, vipAt, waiterId);
        }
        drink(clientId, main, vip, "stout", 2, sendVip, vipAt, waiterId);
        drink(clientId, main, vip, "water", 2, sendVip, vipAt, waiterId);
        times(vip, main, now.minusMinutes(82), vipAt, null, chefId, waiterId, false);
        ticketState(vip, main, false, now.minusMinutes(58), now.minusMinutes(55), chefId, waiterId);
        ticketState(vip, main, true, now.minusMinutes(75), now.minusMinutes(73), chefId, waiterId);
        BigDecimal toPay = billService.get(clientId, waiterId, vip).summary().toPay();
        if (toPay != null && toPay.signum() > 0) {
            billService.pay(clientId, waiterId, vip, new BillService.PayRequest(SalePaymentMethod.CARD, null, main.bar(),
                    toPay.divide(BigDecimal.valueOf(2), 0, RoundingMode.FLOOR)));
            jdbc.update("UPDATE bill_payments SET paid_at = :at WHERE bill_id = :bill",
                    new MapSqlParameterSource("bill", vip).addValue("at", now.minusMinutes(6)));
        }

        UUID takeaway = open(clientId, main, null, "Takeaway 3", waiterId);
        OffsetDateTime takeawayAt = now.minusMinutes(8);
        UUID sendTakeaway = UUID.randomUUID();
        send(clientId, main.branch(), main.kitchen(), takeaway, menu.get("small-chops"), BigDecimal.ONE, price("small-chops"), null, null,
                sendTakeaway, takeawayAt, waiterId);
        send(clientId, main.branch(), main.kitchen(), takeaway, menu.get("jollof"), BigDecimal.valueOf(2), price("jollof"), null, null,
                sendTakeaway, takeawayAt, waiterId);
        times(takeaway, main, now.minusMinutes(9), takeawayAt, null, chefId, waiterId, false);
    }

    private UUID open(UUID clientId, Places main, UUID tableId, String name, UUID waiterId) {
        return billService.open(clientId, waiterId, new BillService.OpenRequest(main.branch(), tableId, name)).id();
    }

    private void drink(UUID clientId, Places main, UUID billId, String itemKey, int quantity, UUID sendKey, OffsetDateTime at,
            UUID waiterId) {
        UUID productId = jdbc.queryForObject("SELECT id FROM products WHERE client_id = :clientId AND sku = :sku",
                new MapSqlParameterSource("clientId", clientIdOf(billId)).addValue("sku", "DM-" + itemKey.toUpperCase().replace('-', '_')),
                UUID.class);
        send(clientId, main.branch(), main.bar(), billId, productId, BigDecimal.valueOf(quantity), item(itemKey).sellPrice(), null, null,
                sendKey, at, waiterId);
    }

    /** One area's ticket on a bill: ready (or not) and served (or not), at these times. */
    private void ticketState(UUID billId, Places main, boolean bar, OffsetDateTime readyAt, OffsetDateTime servedAt, UUID chefId,
            UUID waiterId) {
        jdbc.update("""
                UPDATE tickets SET ready_at = :ready, ready_by = :chef, served_at = :served,
                       served_by = CASE WHEN CAST(:served AS timestamptz) IS NULL THEN NULL ELSE CAST(:waiter AS uuid) END
                WHERE bill_id = :bill AND (area_id = :bar) = :isBar
                """, new MapSqlParameterSource().addValue("bill", billId).addValue("bar", main.bar()).addValue("isBar", bar)
                .addValue("ready", readyAt).addValue("served", servedAt).addValue("chef", chefId).addValue("waiter", waiterId));
    }

    private UUID clientIdOf(UUID billId) {
        return jdbc.queryForObject("SELECT client_id FROM bills WHERE id = :id", new MapSqlParameterSource("id", billId), UUID.class);
    }

    private static BigDecimal price(String dishKey) {
        return MENU.stream().filter(dish -> dish.key().equals(dishKey)).findFirst().map(dish -> new BigDecimal(dish.price()))
                .orElseThrow();
    }

    private static DemoCatalog.Item item(String key) {
        return DemoCatalog.ITEMS.stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow();
    }
}
