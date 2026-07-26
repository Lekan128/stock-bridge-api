package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A tenant user. Extends TenantAwareEntity for client_id - see that class for
 * how it's populated and enforced. Usernames are unique per tenant only (see
 * the (client_id, username) constraint in the schema), not globally.
 *
 * client_id is inherited, read-only from outside this package family, and is
 * always overwritten from TenantContext on persist - even if a builder call
 * set it, it will not survive @PrePersist. The one expected exception is a
 * privileged, server-side-only flow (e.g. a super-admin bootstrapping a new
 * client's first user) which must explicitly TenantContext.set(newClientId)
 * before saving, since a super-admin has no tenant context of its own.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class User extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /**
     * True for the tenant's account creator (the user ClientSignupService makes
     * at signup), at most one per client - enforced by a partial unique index,
     * not just here. This is deliberately not a role: it survives whatever role
     * the account holder is on, and it exists only so another admin can't lock
     * the account holder out or take over their credentials. See
     * UserManagementService for the rules it drives.
     */
    @Column(name = "is_root", nullable = false)
    private boolean root;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    // Contact email, unrelated to username - a sub-user's username needn't be
    // an email (only signup's admin username is required to be one).
    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "job_title", length = 100)
    private String jobTitle;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
