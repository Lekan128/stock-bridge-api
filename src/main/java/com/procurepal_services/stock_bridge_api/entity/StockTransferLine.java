package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One product on a waybill (V53). In the product's stock unit; reached only through its transfer, so
 * it carries no company of its own, like {@code ExpectedDeliveryLine}. What is still on the way is
 * {@link #onTheWay()} - derived, never stock (D-B8).
 */
@Entity
@Table(name = "stock_transfer_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockTransferLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false, updatable = false)
    private StockTransfer transfer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "quantity_sent", nullable = false, updatable = false)
    private int quantitySent;

    @Column(name = "quantity_received", nullable = false)
    private int quantityReceived;

    @Column(name = "quantity_missing", nullable = false)
    private int quantityMissing;

    @Column(name = "quantity_damaged", nullable = false)
    private int quantityDamaged;

    /** Reported missing, then found and put back on a shelf (V54). No longer missing. */
    @Column(name = "quantity_found", nullable = false)
    private int quantityFound;

    /** As typed, for the waybill: display only (V21's rule for entered_*). */
    @Column(name = "entered_unit", length = 32, updatable = false)
    private String enteredUnit;

    @Column(name = "entered_quantity", precision = 14, scale = 3, updatable = false)
    private BigDecimal enteredQuantity;

    /** The TRANSFER_OUT that took it off the sending branch's shelf. */
    @Column(name = "out_movement_id", nullable = false, updatable = false)
    private UUID outMovementId;

    /** How much of this line has been received, reported missing or damaged, or found again. */
    public int accounted() {
        return quantityReceived + quantityMissing + quantityDamaged + quantityFound;
    }

    public int onTheWay() {
        return quantitySent - accounted();
    }
}
