package com.procurepal_services.stock_bridge_api.demo;

import com.procurepal_services.stock_bridge_api.product.unit.ProductMeasure;
import java.math.BigDecimal;
import java.util.List;

/**
 * What the demo restaurant stocks (owner, 2026-11-11: restaurants are the first prospects). Fixed data,
 * so every "Reset demo" builds the same company; only the dates move, ending today.
 *
 * <p>Prices are Lagos prices in naira, late 2026, rounded. Suppliers are made up: their emails are on
 * demo.procurepaddy.com and nothing from a demo company is ever emailed to them anyway (see
 * {@link DemoCompanyGuard}).
 */
public final class DemoCatalog {

    private DemoCatalog() {
    }

    /** Where a product lives in a branch. Deliveries land there; the kitchen or bar draws from it. */
    public enum Home { STORE, COLD_ROOM, BAR }

    public record Supplier(String key, String name, String phone, String city, String notes) {

        public String email() {
            return key + "@demo.procurepaddy.com";
        }
    }

    /**
     * @param key          stable id, also the picture's file name in {@code demo/images/<key>.jpg}
     * @param stockUnit    the unit stock is kept in (KG, LITER, PIECE)
     * @param packUnit     how it is bought (BAG, CARTON...), with {@code packSize} stock units in one
     * @param packCost     what one pack costs from {@code supplierKey}
     * @param sellPrice    a bar price per stock unit, for the drinks sold over the counter; null otherwise
     * @param lowStock     the reorder level, in stock units
     * @param dailyUse     what the main branch gets through on an ordinary day, in stock units
     * @param deliverEvery days between deliveries
     */
    public record Item(
            String key,
            String name,
            String category,
            String stockUnit,
            String packUnit,
            BigDecimal packSize,
            String supplierKey,
            BigDecimal packCost,
            BigDecimal sellPrice,
            int lowStock,
            Home home,
            BigDecimal dailyUse,
            int deliverEvery,
            List<ProductMeasure> measures,
            String usedIn,
            String description) {

        public boolean sold() {
            return sellPrice != null;
        }

        public boolean perishable() {
            return home == Home.COLD_ROOM || "Fresh produce".equals(category);
        }

        /** What one stock unit costs, from the pack price. */
        public BigDecimal unitCost() {
            return packSize == null ? packCost : packCost.divide(packSize, 2, java.math.RoundingMode.HALF_UP);
        }
    }

    public static final String GRAINS = "Grains & dry goods";
    public static final String MEAT = "Meat & fish";
    public static final String FRESH = "Fresh produce";
    public static final String SPICES = "Spices & seasoning";
    public static final String OILS = "Oils";
    public static final String DRINKS = "Drinks";
    public static final String SUPPLIES = "Packaging & cleaning";

    public static final List<String> CATEGORIES = List.of(GRAINS, MEAT, FRESH, SPICES, OILS, DRINKS, SUPPLIES);

    public static final List<Supplier> SUPPLIERS = List.of(
            new Supplier("bodija-provisions", "Bodija Grains & Provisions", "+234 800 000 0101", "Lagos",
                    "Rice, beans, flour and dry goods. Delivers Tuesdays and Fridays."),
            new Supplier("daleko-rice", "Daleko Rice Mill", "+234 800 000 0102", "Lagos",
                    "Second rice supplier. Cheaper by the bag, slower to deliver."),
            new Supplier("mile12-fresh", "Mile 12 Fresh Produce", "+234 800 000 0103", "Lagos",
                    "Tomatoes, peppers, onions and greens. Early morning drop-off."),
            new Supplier("coldchain-foods", "Lekki Cold Chain Foods", "+234 800 000 0104", "Lagos",
                    "Frozen chicken, turkey and fish. Ask for the cold van."),
            new Supplier("okooba-meats", "Oko-Oba Meat Market", "+234 800 000 0105", "Lagos",
                    "Beef and goat meat, cut to order."),
            new Supplier("eko-drinks", "Eko Drinks Depot", "+234 800 000 0106", "Lagos",
                    "Soft drinks, water, malt and beer by the pack or crate."),
            new Supplier("ajah-packaging", "Ajah Packaging Supplies", "+234 800 000 0107", "Lagos",
                    "Takeaway packs, serviettes, cleaning supplies and gas refills."));

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private static List<ProductMeasure> cup(String size) {
        return List.of(new ProductMeasure("Cup", n(size)));
    }

    private static Item item(String key, String name, String category, String stockUnit, String packUnit, String packSize,
            String supplierKey, String packCost, String sellPrice, int lowStock, Home home, String dailyUse,
            int deliverEvery) {
        return new Item(key, name, category, stockUnit, packUnit, packSize == null ? null : n(packSize), supplierKey,
                n(packCost), sellPrice == null ? null : n(sellPrice), lowStock, home, n(dailyUse), deliverEvery,
                List.of(), null, null);
    }

    private static Item measured(Item item, List<ProductMeasure> measures, String usedIn, String description) {
        return new Item(item.key(), item.name(), item.category(), item.stockUnit(), item.packUnit(), item.packSize(),
                item.supplierKey(), item.packCost(), item.sellPrice(), item.lowStock(), item.home(), item.dailyUse(),
                item.deliverEvery(), measures, usedIn, description);
    }

    public static final List<Item> ITEMS = List.of(
            // Grains & dry goods - Store
            measured(item("rice", "Rice (long grain)", GRAINS, "KG", "BAG", "50", "bodija-provisions", "95000", null, 75,
                    Home.STORE, "18", 7), cup("0.2"), null, "Parboiled long grain, 50 kg bags. Jollof and fried rice."),
            measured(item("beans", "Beans (oloyin)", GRAINS, "KG", "BAG", "50", "bodija-provisions", "85000", null, 25,
                    Home.STORE, "5", 10), cup("0.2"), null, "Honey beans for ewa agoyin and moi moi."),
            item("garri", "Garri (white)", GRAINS, "KG", "BAG", "50", "bodija-provisions", "45000", null, 20,
                    Home.STORE, "4", 10),
            item("semovita", "Semovita", GRAINS, "KG", "BAG", "10", "bodija-provisions", "14500", null, 10,
                    Home.STORE, "3", 7),
            item("yam-flour", "Yam flour (elubo)", GRAINS, "KG", "BAG", "25", "bodija-provisions", "62000", null, 10,
                    Home.STORE, "3", 10),
            item("flour", "Flour (baking)", GRAINS, "KG", "BAG", "50", "bodija-provisions", "70000", null, 15,
                    Home.STORE, "3", 14),
            item("spaghetti", "Spaghetti 500g", GRAINS, "PIECE", "CARTON", "20", "bodija-provisions", "22000", null, 20,
                    Home.STORE, "6", 7),
            item("noodles", "Instant noodles 70g", GRAINS, "PIECE", "CARTON", "40", "bodija-provisions", "14000", null, 40,
                    Home.STORE, "12", 7),
            item("sugar", "Sugar", GRAINS, "KG", "BAG", "50", "bodija-provisions", "95000", null, 10,
                    Home.STORE, "1.5", 21),
            item("salt", "Salt", SPICES, "KG", "BAG", "25", "bodija-provisions", "12000", null, 8,
                    Home.STORE, "1", 21),
            item("tomato-paste", "Tomato paste 400g tin", GRAINS, "PIECE", "CARTON", "24", "bodija-provisions", "36000", null,
                    24, Home.STORE, "8", 7),
            item("plantain", "Plantain", FRESH, "PIECE", "BUNDLE", "20", "mile12-fresh", "9000", null, 30,
                    Home.STORE, "25", 3),
            item("yam", "Yam (tuber)", FRESH, "PIECE", null, null, "mile12-fresh", "4500", null, 6,
                    Home.STORE, "3", 7),
            item("eggs", "Eggs", FRESH, "PIECE", "TRAY", "30", "mile12-fresh", "5800", null, 60,
                    Home.STORE, "45", 4),

            // Spices & seasoning
            item("seasoning", "Seasoning cubes (pack of 100)", SPICES, "PIECE", "CARTON", "24", "bodija-provisions", "60000",
                    null, 6, Home.STORE, "1.5", 14),
            item("curry", "Curry powder 100g", SPICES, "PIECE", "BOX", "12", "bodija-provisions", "9600", null, 4,
                    Home.STORE, "0.5", 21),
            item("thyme", "Thyme 100g", SPICES, "PIECE", "BOX", "12", "bodija-provisions", "9000", null, 4,
                    Home.STORE, "0.5", 21),
            measured(item("crayfish", "Crayfish (ground)", SPICES, "KG", null, null, "bodija-provisions", "12000", null, 3,
                    Home.STORE, "0.8", 7), cup("0.15"), "Cup", "Ground crayfish for soups and stews."),
            measured(item("egusi", "Egusi (ground melon)", SPICES, "KG", "BAG", "10", "bodija-provisions", "60000", null, 4,
                    Home.STORE, "1.5", 10), cup("0.25"), "Cup", null),
            item("ogbono", "Ogbono (ground)", SPICES, "KG", null, null, "bodija-provisions", "10000", null, 2,
                    Home.STORE, "0.6", 10),
            item("stockfish", "Stockfish", MEAT, "KG", null, null, "coldchain-foods", "25000", null, 2,
                    Home.STORE, "0.5", 14),
            item("dried-fish", "Dried fish", MEAT, "KG", null, null, "coldchain-foods", "9000", null, 2,
                    Home.STORE, "0.7", 7),

            // Oils
            item("veg-oil", "Vegetable oil", OILS, "LITER", "KEG", "25", "bodija-provisions", "75000", null, 20,
                    Home.STORE, "7", 7),
            item("palm-oil", "Palm oil", OILS, "LITER", "KEG", "25", "bodija-provisions", "60000", null, 10,
                    Home.STORE, "3", 10),

            // Meat & fish - Cold room
            item("chicken", "Whole chicken (frozen)", MEAT, "KG", "CARTON", "10", "coldchain-foods", "55000", null, 20,
                    Home.COLD_ROOM, "14", 4),
            item("turkey", "Turkey wings", MEAT, "KG", "CARTON", "10", "coldchain-foods", "70000", null, 10,
                    Home.COLD_ROOM, "6", 5),
            item("beef", "Beef (boneless)", MEAT, "KG", null, null, "okooba-meats", "8000", null, 10,
                    Home.COLD_ROOM, "8", 3),
            item("goat", "Goat meat", MEAT, "KG", null, null, "okooba-meats", "9500", null, 6,
                    Home.COLD_ROOM, "4", 4),
            item("croaker", "Croaker fish", MEAT, "KG", "CARTON", "10", "coldchain-foods", "65000", null, 8,
                    Home.COLD_ROOM, "4", 5),
            item("titus", "Mackerel (titus)", MEAT, "KG", "CARTON", "20", "coldchain-foods", "100000", null, 10,
                    Home.COLD_ROOM, "4", 7),
            item("snail", "Snails (large)", MEAT, "PIECE", null, null, "okooba-meats", "1500", null, 10,
                    Home.COLD_ROOM, "6", 7),

            // Fresh produce - Cold room
            item("tomatoes", "Tomatoes", FRESH, "KG", "BASKET", "25", "mile12-fresh", "40000", null, 15,
                    Home.COLD_ROOM, "9", 3),
            item("tatashe", "Tatashe (red bell pepper)", FRESH, "KG", "BASKET", "10", "mile12-fresh", "25000", null, 5,
                    Home.COLD_ROOM, "3", 3),
            item("ata-rodo", "Scotch bonnet (ata rodo)", FRESH, "KG", null, null, "mile12-fresh", "3000", null, 2,
                    Home.COLD_ROOM, "1.2", 3),
            item("onions", "Onions", FRESH, "KG", "BAG", "50", "mile12-fresh", "70000", null, 15,
                    Home.STORE, "6", 7),
            item("ugu", "Ugu leaves", FRESH, "KG", null, null, "mile12-fresh", "2000", null, 2,
                    Home.COLD_ROOM, "1.5", 2),
            item("ginger", "Ginger", FRESH, "KG", null, null, "mile12-fresh", "3000", null, 1,
                    Home.STORE, "0.4", 7),
            item("garlic", "Garlic", FRESH, "KG", null, null, "mile12-fresh", "4000", null, 1,
                    Home.STORE, "0.3", 7),
            item("carrots", "Carrots", FRESH, "KG", null, null, "mile12-fresh", "2000", null, 3,
                    Home.COLD_ROOM, "1.5", 4),
            item("cabbage", "Cabbage", FRESH, "PIECE", null, null, "mile12-fresh", "1500", null, 4,
                    Home.COLD_ROOM, "2", 4),

            // Drinks - Bar, sold over the counter
            item("cola", "Cola 50cl (PET)", DRINKS, "PIECE", "PACK", "12", "eko-drinks", "6000", "800", 24,
                    Home.BAR, "30", 7),
            item("orange-soda", "Orange soda 50cl (PET)", DRINKS, "PIECE", "PACK", "12", "eko-drinks", "6000", "800", 12,
                    Home.BAR, "14", 7),
            item("water", "Table water 75cl", DRINKS, "PIECE", "PACK", "12", "eko-drinks", "3000", "400", 24,
                    Home.BAR, "36", 7),
            item("malt", "Malt drink 33cl can", DRINKS, "PIECE", "PACK", "24", "eko-drinks", "14400", "1000", 24,
                    Home.BAR, "16", 7),
            item("lager", "Lager beer 60cl", DRINKS, "PIECE", "CRATE", "12", "eko-drinks", "12000", "1500", 24,
                    Home.BAR, "20", 7),
            item("stout", "Stout 60cl", DRINKS, "PIECE", "CRATE", "12", "eko-drinks", "14400", "1800", 12,
                    Home.BAR, "8", 7),
            item("juice", "Fruit juice 1L", DRINKS, "PIECE", "CARTON", "10", "eko-drinks", "18000", "2500", 6,
                    Home.BAR, "5", 10),

            // Packaging & cleaning - Store
            item("takeaway", "Takeaway packs (foil, 1000ml)", SUPPLIES, "PIECE", "CARTON", "200", "ajah-packaging", "30000",
                    null, 100, Home.STORE, "60", 7),
            item("serviettes", "Serviettes (roll)", SUPPLIES, "PIECE", "CARTON", "24", "ajah-packaging", "18000", null, 12,
                    Home.STORE, "5", 14),
            item("dish-soap", "Dishwashing liquid", SUPPLIES, "LITER", "KEG", "25", "ajah-packaging", "25000", null, 6,
                    Home.STORE, "2", 14),
            item("gas", "Cooking gas (LPG)", SUPPLIES, "KG", null, null, "ajah-packaging", "1300", null, 25,
                    Home.STORE, "12", 7));
}
