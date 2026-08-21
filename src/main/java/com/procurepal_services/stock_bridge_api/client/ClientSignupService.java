package com.procurepal_services.stock_bridge_api.client;

import com.procurepal_services.stock_bridge_api.auth.AuthService;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.email.verification.EmailVerificationService;
import com.procurepal_services.stock_bridge_api.email.verification.VerificationLink;
import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.user.TenantRoles;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service tenant signup: creates a client, its default 'Head Office' branch
 * and its first user - the OWNER, flagged as root - in one transaction, then logs
 * that user in immediately. The client-identifier
 * uniqueness check is pre-checked (clean 409 for the common case) and also
 * backstopped by the DB's unique constraint via ClientSignupExceptionHandler
 * for the rare concurrent-signup race - see that class for why it isn't
 * caught inline here (Postgres poisons the transaction on a constraint
 * violation, so catching and continuing in the same transaction isn't safe;
 * letting it propagate lets Spring roll back cleanly before translation).
 */
@Service
@RequiredArgsConstructor
public class ClientSignupService {

    /** The one branch every client starts with. Also the name V6's backfill uses, deliberately. */
    private static final String DEFAULT_BRANCH_NAME = "Head Office";

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final EmailNotificationService emailNotificationService;
    private final EmailVerificationService emailVerificationService;

    @Transactional
    public TenantLoginResponse signup(ClientSignupRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new PasswordMismatchException();
        }

        String slug = resolveSlug(request);
        if (clientRepository.findBySlug(slug).isPresent()) {
            throw new ClientIdentifierTakenException(slug);
        }

        Role ownerRole = roleRepository.findByName(TenantRoles.OWNER)
                .orElseThrow(() -> new IllegalStateException("OWNER role not seeded - run the Flyway migrations"));

        Client client = clientRepository.save(Client.builder()
                .name(request.name())
                .slug(slug)
                .adminContactEmail(request.adminEmail())
                .phone(normalize(request.phone()))
                // Not settable at signup on purpose: a self-service tenant cannot
                // declare itself the marketplace operator, and it cannot grant
                // itself credit terms. Both default appropriately (false / PREPAID).
                .active(true)
                .build());

        // No tenant context exists yet for a brand-new client (there's no
        // authenticated principal on this public endpoint) - set it explicitly
        // for this one privileged, server-side write, same pattern as any other
        // system-initiated first-user creation. See User's class doc.
        TenantContext.set(client.getId());
        User ownerUser;
        try {
            // Every client has exactly one default branch, created here in the same
            // transaction as the client rather than lazily on first use. The
            // alternative - "create it when something needs one" - means every
            // caller has to handle the absent case, and the DB's
            // one-default-per-client index means a lazy race could fail in a place
            // that has nothing to do with branches. V6__marketplace.sql does the
            // equivalent backfill for clients created before this existed.
            branchRepository.save(Branch.builder()
                    .name(DEFAULT_BRANCH_NAME)
                    .defaultBranch(true)
                    .active(true)
                    .build());

            ownerUser = userRepository.save(User.builder()
                    .username(request.adminEmail())
                    .passwordHash(passwordEncoder.encode(request.password()))
                    .role(ownerRole)
                    .active(true)
                    // Signup is the moment where "who owns this account" is
                    // unambiguous, so the first user is the account holder and every
                    // later one, created by somebody else, is a sub-user.
                    //
                    // Two other server-side flows also create a root user, under the
                    // same condition - a client with no users yet needs an account
                    // holder, and only privileged code can say who it is:
                    // PlatformOwnerBootstrapRunner (from env vars, at startup) and
                    // SuperAdminUserService.createPlatformOwnerUser (for ProcurePal's
                    // first user). The rule none of them breaks is that root is never
                    // something a CALLER can ask for: it is derived server-side, and
                    // no request DTO in this codebase has a root component.
                    // UserManagementService hardcodes root(false) for exactly that
                    // reason - nothing created through the tenant-facing API is ever
                    // the account holder.
                    .root(true)
                    .email(request.adminEmail())
                    .build());
        } finally {
            TenantContext.clear();
        }

        // After the TenantContext finally-block, not inside it: rendering this reads
        // the client and the user, and doing that under a tenant context set purely
        // to permit one privileged insert would tie the email to a scope it has no
        // business depending on. It is dispatched, not sent - the actual send waits
        // for this transaction to commit, so a signup that fails at the last hurdle
        // does not welcome anybody to an account that does not exist.
        //
        // The verification token is minted here for the SAME two reasons, which is
        // why it sits inside the same block rather than up beside the user insert.
        // email_verification_tokens is not a tenant-scoped table - it deliberately
        // has no client_id, so that the unauthenticated redemption endpoint can read
        // it at all - so writing it under a borrowed tenant context would suggest a
        // dependency that does not exist. And it is written in THIS transaction, so
        // a signup that rolls back takes the token with it: a live link to an
        // account that was never created would be a link that can only ever fail.
        //
        // One email, not two. The link rides on the welcome rather than arriving as
        // a separate "confirm your address" message seconds later - see
        // AccountEmails.welcome for why that matters more than it looks like it
        // should. A null link (no plausible address, or no configured base URL)
        // renders the welcome exactly as it was before this flow existed.
        VerificationLink verificationLink = emailVerificationService.issueLink(ownerUser);
        emailNotificationService.welcomeNewClient(client, ownerUser, verificationLink);

        return authService.issueLoginResponse(ownerUser, client);
    }

    private String resolveSlug(ClientSignupRequest request) {
        String source = (request.clientIdentifier() != null && !request.clientIdentifier().isBlank())
                ? request.clientIdentifier()
                : request.name();
        return slugify(source);
    }

    /** Blank is how a form says "empty"; the database should say NULL. */
    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String slugify(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "client-" + UUID.randomUUID() : normalized;
    }
}
