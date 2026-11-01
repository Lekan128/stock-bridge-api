package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * A product's figure at one branch (V51), mapped read-only so the products list can filter by a
 * branch's own figure and level in one query (MULTI_BRANCH_BUILD_PLAN.md section 6, Phase 4). Writes
 * go through {@code BranchStockService} only, never this.
 */
@Entity
@Immutable
@Table(name = "branch_stock")
@IdClass(BranchStockEntry.Key.class)
@Getter
@NoArgsConstructor
public class BranchStockEntry {

    @Id
    @Column(name = "branch_id")
    private UUID branchId;

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "quantity_on_hand")
    private int quantityOnHand;

    @Column(name = "low_stock_threshold")
    private Integer lowStockThreshold;

    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID branchId;
        private UUID productId;
    }
}
