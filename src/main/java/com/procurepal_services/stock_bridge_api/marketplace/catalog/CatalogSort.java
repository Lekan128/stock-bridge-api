package com.procurepal_services.stock_bridge_api.marketplace.catalog;

/**
 * The storefront's sort options, as a closed set.
 *
 * A closed enum rather than Spring Data's free-form {@code sort=property,dir} on
 * purpose: this endpoint is public and unauthenticated, so anything that lets a
 * caller name a persistent property is a small information-disclosure surface
 * (ordering by cost_price would leak ProcurePal's margins to anyone with a
 * browser). It also keeps the option list something the UI can render as a
 * dropdown without knowing the schema.
 *
 * The contract fixes these five names; the frontend's CatalogSort union mirrors
 * them exactly.
 */
public enum CatalogSort {

    /**
     * The default. Purchasable items first, then alphabetical.
     *
     * Deliberately NOT a text-similarity score: Postgres full-text/trigram ranking
     * would need an index and a migration this module is not allowed to add, and a
     * LIKE-based catalog of ~35 items gains nothing from it. What actually helps a
     * buyer is not being shown three out-of-stock bags of rice above the one that
     * can ship today, which is what this ordering does. Revisit when the catalog is
     * large enough that ranking beats availability.
     */
    RELEVANCE,

    PRICE_ASC,
    PRICE_DESC,
    NAME_ASC,

    /** Newest listing first - what "New arrivals" merchandising needs. */
    NEWEST
}
