package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Either a system-defined role (OWNER, PROCUREMENT_MANAGER, INVENTORY_OFFICER,
 * FINANCE_OFFICER, STOREKEEPER, VENDOR - clientId null, shared across every
 * tenant, fixed and not editable through the Roles & Privileges screen) or a
 * tenant's own custom role (clientId set - see RoleManagementService). Names
 * are unique among system roles, and separately unique per tenant among
 * custom roles (V27), not globally - two tenants may each have a "Warehouse
 * Clerk".
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 255)
    private String description;

    /** Null for a system role. Set for a tenant's own custom role - never reassigned. */
    @Column(name = "client_id")
    private UUID clientId;

    @Builder.Default
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    public boolean isSystem() {
        return clientId == null;
    }
}
