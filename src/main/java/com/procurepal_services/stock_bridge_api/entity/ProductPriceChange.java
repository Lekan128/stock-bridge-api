package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One change to a product's selling price - old, new, who, when, through what (V47). Append-only.
 * Owners worry about staff quietly changing prices; this is the record that answers them.
 *
 * <p>client_id is set explicitly rather than stamped from the tenant context, so an import commit
 * running on a worker writes it the same as a request does.
 */
@Entity
@Table(name = "product_price_changes")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductPriceChange {

    /** Where a price change came from. */
    public enum Source {
        /** The product form. */
        FORM,
        /** A product sheet's update row. */
        IMPORT,
        /** Undoing a sheet put the old price back. */
        IMPORT_UNDO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "old_unit_price", precision = 14, scale = 2, updatable = false)
    private BigDecimal oldUnitPrice;

    @Column(name = "new_unit_price", precision = 14, scale = 2, updatable = false)
    private BigDecimal newUnitPrice;

    @Column(name = "old_entered_price", precision = 14, scale = 2, updatable = false)
    private BigDecimal oldEnteredPrice;

    @Column(name = "old_entered_unit", length = 32, updatable = false)
    private String oldEnteredUnit;

    @Column(name = "new_entered_price", precision = 14, scale = 2, updatable = false)
    private BigDecimal newEnteredPrice;

    @Column(name = "new_entered_unit", length = 32, updatable = false)
    private String newEnteredUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    private Source source;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private OffsetDateTime changedAt;
}
