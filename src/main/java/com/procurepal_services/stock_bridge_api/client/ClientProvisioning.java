package com.procurepal_services.stock_bridge_api.client;

import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The parts of "bring a new tenant into existence" that are identical whoever is
 * doing it - lifted out of {@link ClientSignupService} when super admins gained
 * the ability to create vendor accounts, which is a second flow that must produce
 * a client indistinguishable in shape from a self-service one.
 *
 * <h2>What is here, and why exactly these two things</h2>
 * The test applied was: would two copies of this drift, and would the drift be
 * silent?
 * <ul>
 *   <li><strong>The slug.</strong> Yes on both counts, and expensively. The slug
 *       is what a user types at login, so two flows that derive it differently
 *       produce accounts that behave differently at the one moment a customer
 *       cannot work around it. A second implementation that allowed, say, a
 *       trailing hyphen would create logins nobody could guess, and nothing would
 *       fail until somebody tried to sign in.</li>
 *   <li><strong>The default branch.</strong> Yes, quietly. Every client is created
 *       with exactly one branch, in the same transaction as the client rather than
 *       lazily, so that no caller has to handle the absent case - the reasoning is
 *       {@link ClientSignupService}'s and is unchanged. A vendor created without
 *       one would look fine until its first stock movement, which is well past the
 *       point anybody would connect the failure to the account's creation.</li>
 * </ul>
 *
 * <h2>What is deliberately NOT here, and why duplication is right for it</h2>
 * The user insert itself. It is tempting - both flows create one root user with a
 * bcrypt hash - and it would be wrong, because almost every field differs and the
 * differences are the whole point of the two flows: OWNER against VENDOR, a
 * required email against a nullable one, {@code TenantContext.set} in a finally
 * against {@code TenantScopeExecutor.callAs} (a super admin has no context to
 * restore, a signing-up user has none either but for the opposite reason), and a
 * login response against no response at all. A shared method would need a
 * parameter for each, at which point it is a builder with a different name that
 * hides which flow does what. The one invariant they genuinely share -
 * {@code root} is derived server-side and never read from a request - is stated
 * in {@code User}'s class doc and enforced by no request DTO having the
 * component, which is a stronger guarantee than a shared method would give.
 *
 * <p>Password hashing is not shared either, and does not need to be: it is
 * {@code passwordEncoder.encode(...)} against the one {@code PasswordEncoder}
 * bean {@code SecurityConfig} defines, so both flows already use the same
 * algorithm by construction rather than by agreement.
 */
@Component
@RequiredArgsConstructor
public class ClientProvisioning {

    /**
     * The one branch every client starts with. Also the name V6's backfill uses,
     * deliberately - moved here from {@link ClientSignupService} so both creation
     * paths cannot name it differently.
     */
    public static final String DEFAULT_BRANCH_NAME = "Head Office";

    private final ClientRepository clientRepository;
    private final BranchRepository branchRepository;

    /**
     * Turns a preferred identifier (or, when that is blank, the company's name)
     * into a login slug, and refuses one that is taken.
     *
     * <p>The pre-check is for the message, not for the guarantee -
     * {@code uq_clients_slug} is the guarantee, and two creations racing the same
     * identifier will have one hit it. That lands as a
     * {@code DataIntegrityViolationException} which each surface's advice already
     * turns into a 409. {@link ClientIdentifierTakenException} is reused rather
     * than re-invented for the same reason {@code SuperAdminClientService} reuses
     * it when renaming: a caller should not have to learn two vocabularies for one
     * collision.
     *
     * @throws ClientIdentifierTakenException if the resulting slug already belongs
     *     to a client
     */
    public String requireAvailableSlug(String preferred, String fallbackSource) {
        String source = (preferred != null && !preferred.isBlank()) ? preferred : fallbackSource;
        String slug = slugify(source);
        if (clientRepository.findBySlug(slug).isPresent()) {
            throw new ClientIdentifierTakenException(slug);
        }
        return slug;
    }

    /**
     * Lowercased, non-alphanumerics collapsed to single hyphens, no leading or
     * trailing hyphen.
     *
     * <p>The random fallback covers a name with no alphanumeric characters at all -
     * rare, but the column is NOT NULL and a blank slug would fail at the database
     * with a message about a constraint rather than about the name that caused it.
     */
    public String slugify(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "client-" + UUID.randomUUID() : normalized;
    }

    /**
     * Creates the client's single default branch.
     *
     * <p><strong>Requires a tenant context already set to the new client</strong> -
     * {@link Branch} is tenant-aware and its {@code @PrePersist} refuses to persist
     * without one rather than guess. Both callers are inside such a scope when they
     * reach here ({@code TenantContext.set} at signup,
     * {@code TenantScopeExecutor.callAs} on the super-admin path), which is why
     * this takes no client id: an id parameter would look like it set the scope,
     * and a caller who passed one without opening a scope would get a confusing
     * failure from a class that appeared to have handled it.
     */
    public Branch createDefaultBranch() {
        return branchRepository.save(Branch.builder()
                .name(DEFAULT_BRANCH_NAME)
                .defaultBranch(true)
                .active(true)
                .build());
    }
}
