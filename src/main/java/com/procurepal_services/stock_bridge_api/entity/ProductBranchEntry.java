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
 * A branch a product is for (V92) - read-only here, for list queries' "is it for this branch" subquery. Written
 * through {@code ProductBranchService} only.
 */
@Entity
@Immutable
@Table(name = "product_branches")
@IdClass(ProductBranchEntry.Key.class)
@Getter
@NoArgsConstructor
public class ProductBranchEntry {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Id
    @Column(name = "branch_id")
    private UUID branchId;

    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID productId;
        private UUID branchId;
    }
}
