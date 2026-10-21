package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One change to a company setting - who, from where, old to new, and why (V45). Append-only, like
 * {@link VendorSettlementSettingsChange}: written once, never updated.
 *
 * <p>Not tenant-filtered on purpose: support writes it for a client it is not signed in to, and it
 * is only ever read by client id.
 */
@Entity
@Table(name = "client_setting_changes")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientSettingChange {

    /** The only setting so far. A name rather than a column so the next switch logs here too. */
    public static final String SELLS_STOCK = "SELLS_STOCK";

    /** Who made a change: a tenant user ({@code users.id}) or support ({@code super_admins.id}). */
    public enum ChangedByKind {
        TENANT_USER,
        SUPER_ADMIN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(name = "setting", nullable = false, updatable = false, length = 40)
    private String setting;

    @Column(name = "old_value", updatable = false, length = 100)
    private String oldValue;

    @Column(name = "new_value", nullable = false, updatable = false, length = 100)
    private String newValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    private SettingChangeSource source;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_kind", nullable = false, updatable = false, length = 20)
    private ChangedByKind changedByKind;

    @Column(name = "changed_by_username", nullable = false, updatable = false, length = 255)
    private String changedByUsername;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private OffsetDateTime changedAt;
}
