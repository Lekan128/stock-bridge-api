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
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One product on an {@link ExpectedDelivery}: what was ordered, in the words it was ordered in.
 *
 * <p>{@code unit} is the same {@code UnitOptions.key} a typed delivery line carries - {@code
 * "BAG:50"} for a 50 kg bag, {@code "KG"} for the product's own unit - and {@code quantity} counts
 * those. Nothing is converted to stock units here on purpose: a conversion done now would have to
 * be undone or re-done if the product's packs change before the goods arrive, and the receipt
 * already converts through the one path that does it correctly.
 */
@Entity
@Table(name = "expected_delivery_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExpectedDeliveryLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expected_delivery_id", nullable = false)
    private ExpectedDelivery expectedDelivery;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 64)
    private String unit;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;

    /** What one of {@code unit} was expected to cost. Nullable - often not agreed up front. */
    @Column(precision = 18, scale = 2)
    private BigDecimal price;

    @Column(name = "received_quantity", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** What is still owed, never negative - an over-delivery settles the line, it does not owe back. */
    public BigDecimal outstanding() {
        BigDecimal remaining = quantity.subtract(receivedQuantity);
        return remaining.signum() <= 0 ? BigDecimal.ZERO : remaining;
    }

    /** True once at least the expected quantity has been received. */
    public boolean isSettled() {
        return receivedQuantity.compareTo(quantity) >= 0;
    }

    /**
     * Credits a receipt, and returns what was actually credited. Clamped at zero on the way down
     * so an undo of more than was ever credited - two undos racing, a hand-edited row - leaves the
     * line at zero rather than owing a negative amount.
     */
    public void credit(BigDecimal amount) {
        BigDecimal next = receivedQuantity.add(amount);
        receivedQuantity = next.signum() < 0 ? BigDecimal.ZERO : next;
    }
}
