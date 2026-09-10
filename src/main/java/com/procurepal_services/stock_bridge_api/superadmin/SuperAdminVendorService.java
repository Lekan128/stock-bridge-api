package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.client.ClientProvisioning;
import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.email.verification.EmailVerificationService;
import com.procurepal_services.stock_bridge_api.email.verification.VerificationLink;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistApplication;
import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistStatus;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.repository.VendorWaitlistApplicationRepository;
import com.procurepal_services.stock_bridge_api.superadmin.dto.ApproveVendorApplicationRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.CreateVendorRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.RejectVendorApplicationRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminVendorDetail;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminVendorSummary;
import com.procurepal_services.stock_bridge_api.superadmin.dto.UpdateVendorRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.VendorApplicationResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.VendorWaitlistCounts;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import com.procurepal_services.stock_bridge_api.tenant.TenantScopeExecutor;
import com.procurepal_services.stock_bridge_api.user.PasswordMismatchException;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import com.procurepal_services.stock_bridge_api.user.TenantRoles;
import com.procurepal_services.stock_bridge_api.vendor.VendorSingleAccountRule;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vendor half of /api/superadmin: the waitlist queue, the decision on each
 * application, and the vendor accounts that result - including the ones that were
 * never applications at all.
 *
 * <h2>Creating a vendor is the only way a VENDOR-role user ever exists</h2>
 * That is the load-bearing property of this class and it is worth stating before
 * anything else. {@code TenantRoles.VENDOR} is deliberately excluded from
 * {@code TenantRoles.ALL}, which is the allow-list both
 * {@code UserManagementService} and {@code SuperAdminUserService} validate a
 * requested role against, and {@code RoleCatalogService.list} filters GET
 * /api/roles down to the same set. So no tenant OWNER can create a vendor-role
 * user inside their own company, and no picker even offers it. This class is the
 * single exception, and it does not break the rule so much as sit outside it: it
 * looks the role up by the {@code TenantRoles.VENDOR} constant directly rather
 * than going through the allow-list, and the role name is never read from a
 * request body on any path below. A future edit that let a caller name a role
 * here would quietly undo the whole arrangement.
 *
 * <h2>No TenantContext, and what that forces</h2>
 * A super admin belongs to no tenant ({@code SuperAdminPrincipal} is not a
 * {@code TenantPrincipal}), so {@code TenantResolutionFilter} leaves the context
 * empty and the Hibernate tenant filter disabled for every request here. Reads are
 * fine on that basis - {@link Client} is not tenant-scoped at all, and the
 * {@code ...ByClientId} finders are plain predicates that hold on their own, which
 * is layer 2 of the two-layer scheme.
 *
 * <p>Writes are not. {@code TenantAwareEntity}'s {@code @PrePersist} REFUSES to
 * persist a {@link User} or a Branch with no tenant context rather than guess, so
 * both creation paths wrap those inserts in {@link TenantScopeExecutor#callAs},
 * which moves the context and the filter together and restores both in a finally.
 * Same arrangement, same reasoning as {@code SuperAdminUserService} - read that
 * class's "Why the reads need no TenantScopeExecutor and the writes do" section.
 * The client id handed to the executor is always {@code vendor.getId()} from a row
 * this class just created or loaded, never a path variable.
 *
 * <h2>Where the transaction boundary is, and why the emails are safe inside it</h2>
 * {@link #approve} does five things that must all happen or none: create the
 * client, create its single user, stamp the application APPROVED, name the client
 * it created, and record who decided and when. Two database CHECKs
 * ({@code chk_vendor_waitlist_approved_has_client},
 * {@code chk_vendor_waitlist_pending_is_unreviewed}) exist precisely because a
 * half-written version of that is invisible afterwards, so it is one
 * {@code @Transactional} method and the constraints are the backstop rather than
 * the plan.
 *
 * <p>The emails ride along without endangering it, on the two guarantees
 * {@code VendorWaitlistService} sets out at length: {@code EmailNotificationService}
 * opens no transaction of its own and swallows everything rendering can throw, and
 * {@code EmailDispatcher} defers the actual send to {@code afterCommit}. A vendor
 * is never mailed an approval for an account that failed to commit, and a bad
 * address can never cost somebody their approval.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminVendorService {

    private final VendorWaitlistApplicationRepository applicationRepository;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ProductRepository productRepository;
    private final ClientProvisioning clientProvisioning;
    private final TenantScopeExecutor tenantScopeExecutor;
    private final PasswordEncoder passwordEncoder;
    private final EmailNotificationService emailNotificationService;
    private final EmailVerificationService emailVerificationService;
    private final VendorSingleAccountRule vendorSingleAccountRule;

    // ------------------------------------------------------------------------
    // The waitlist queue.
    // ------------------------------------------------------------------------

    /**
     * The review queue, optionally filtered by status.
     *
     * <p>The two branches use different repository finders rather than one
     * Specification, and they order differently on purpose. A filtered list is the
     * PENDING queue nine times out of ten and is ordered oldest-first, because the
     * business that has waited longest goes next - that is what a queue means, and
     * {@code idx_vendor_waitlist_status_created_at} is built for it. An unfiltered
     * list is not a queue at all, it is a history, and history reads newest-first.
     *
     * <p>The incoming {@code Pageable}'s sort is deliberately not honoured. Both
     * finders name their order in the method name, so a caller passing {@code
     * ?sort=} gets the queue order regardless. That is the right trade for a screen
     * with two tabs and no column headers; if sortable columns are ever added, this
     * becomes a Specification and the ordering moves into the Pageable.
     */
    @Transactional(readOnly = true)
    public Page<VendorApplicationResponse> listApplications(VendorWaitlistStatus status, Pageable pageable) {
        Page<VendorWaitlistApplication> page = status == null
                ? applicationRepository.findAllByOrderByCreatedAtDesc(pageable)
                : applicationRepository.findAllByStatusOrderByCreatedAtAsc(status, pageable);
        return page.map(VendorApplicationResponse::from);
    }

    @Transactional(readOnly = true)
    public VendorApplicationResponse getApplication(UUID applicationId) {
        return VendorApplicationResponse.from(findApplicationOrThrow(applicationId));
    }

    /** Three indexed counts, for the nav badge and the queue's tab labels. See VendorWaitlistCounts. */
    @Transactional(readOnly = true)
    public VendorWaitlistCounts counts() {
        return new VendorWaitlistCounts(
                applicationRepository.countByStatus(VendorWaitlistStatus.PENDING),
                applicationRepository.countByStatus(VendorWaitlistStatus.APPROVED),
                applicationRepository.countByStatus(VendorWaitlistStatus.REJECTED));
    }

    // ------------------------------------------------------------------------
    // The decision.
    // ------------------------------------------------------------------------

    /**
     * Approves an application and creates the vendor it describes: a
     * {@code clients} row with {@link ClientType#VENDOR}, its default branch, and
     * its single user carrying the VENDOR role - then stamps the application and
     * emails the applicant. All in one transaction; see the class doc.
     *
     * <h2>The pending check is first, and is the whole reason this method is not
     * idempotent-by-accident</h2>
     * Without it, approving twice creates a SECOND client and repoints
     * {@code approved_client_id} at it, leaving the first vendor account orphaned -
     * a live login attached to no application, which nothing downstream would ever
     * surface. Approving an already-rejected application would mail somebody an
     * account minutes after mailing them a refusal. Neither is caught by a
     * constraint ({@code chk_vendor_waitlist_approved_has_client} is satisfied by
     * the second approval just as well as by the first), so it has to be here, and
     * it has to be before anything is created. See
     * {@link VendorApplicationAlreadyReviewedException}.
     *
     * <h2>Everything the applicant told us is taken from the row, not the body</h2>
     * Name, email, phone and the four address columns come off the application.
     * {@code ApproveVendorApplicationRequest} explains why: retyping them invites
     * typos into fields we have verbatim, and lets the approved account disagree
     * with the application that {@code approved_client_id} says produced it.
     */
    @Transactional
    public SuperAdminVendorDetail approve(
            UUID applicationId, ApproveVendorApplicationRequest request, UUID reviewerId) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new PasswordMismatchException();
        }

        VendorWaitlistApplication application = findApplicationOrThrow(applicationId);
        requirePending(application);

        VendorAccount account = createVendorAccount(new VendorSpec(
                application.getBusinessName(),
                request.clientIdentifier(),
                application.getEmail(),
                application.getContactPhone(),
                application.getAddressLine1(),
                application.getAddressLine2(),
                application.getCity(),
                application.getState(),
                request.commissionRate(),
                request.username(),
                request.password()));

        application.setStatus(VendorWaitlistStatus.APPROVED);
        application.setApprovedClientId(account.client().getId());
        application.setReviewedBy(reviewerId);
        application.setReviewedAt(OffsetDateTime.now());
        application.setReviewNote(normalize(request.reviewNote()));
        // saveAndFlush, not a dirty-check at commit: the two CHECKs on this table
        // fire at INSERT/UPDATE time, so flushing here means a half-set decision
        // surfaces inside the request as a 409 rather than during the commit that
        // happens after the response has already been serialised. Same reasoning
        // CompanyService and SuperAdminClientService give for their own flushes.
        applicationRepository.saveAndFlush(application);

        // After the row is durable in this transaction, and before it commits: the
        // dispatcher holds the send until afterCommit, so an approval that fails at
        // the last hurdle does not welcome anybody to an account that does not
        // exist. The verification link is minted here for the same reason the
        // signup flow mints its own where it does - it is written in THIS
        // transaction, so a rollback takes the token with it and there is never a
        // live link to an account that was never created.
        VerificationLink link = emailVerificationService.issueLink(account.user());
        emailNotificationService.vendorApplicationApproved(
                application, account.client(), account.user(), link);

        return toDetail(account.client());
    }

    /**
     * Declines an application, with a note the applicant will read.
     *
     * <p>Rejected rather than deleted, on the same reasoning
     * {@code VendorWaitlistStatus.REJECTED} gives: reapplication is legitimate (no
     * unique index on email, deliberately), and the second application is only
     * useful to a reviewer who can see the first one and why it went the way it
     * did.
     *
     * <p>Note the three columns NOT set here. {@code approved_client_id} stays null
     * because nothing was created, which is what
     * {@code chk_vendor_waitlist_approved_has_client} allows only for a non-APPROVED
     * row - the constraint and this method agree by construction rather than by
     * coincidence.
     */
    @Transactional
    public VendorApplicationResponse reject(
            UUID applicationId, RejectVendorApplicationRequest request, UUID reviewerId) {
        VendorWaitlistApplication application = findApplicationOrThrow(applicationId);
        requirePending(application);

        application.setStatus(VendorWaitlistStatus.REJECTED);
        application.setReviewNote(request.reviewNote().trim());
        application.setReviewedBy(reviewerId);
        application.setReviewedAt(OffsetDateTime.now());
        applicationRepository.saveAndFlush(application);

        emailNotificationService.vendorApplicationRejected(application);

        return VendorApplicationResponse.from(application);
    }

    // ------------------------------------------------------------------------
    // Vendor accounts.
    // ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<SuperAdminVendorSummary> listVendors(String search, Boolean active, Pageable pageable) {
        return clientRepository
                .findAll(ClientSpecifications.vendors(search, active), pageable)
                .map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public SuperAdminVendorDetail getVendor(UUID vendorId) {
        return toDetail(findVendorOrThrow(vendorId));
    }

    /**
     * Creates a vendor with no application behind it - a business ProcurePal
     * recruited rather than one that came to us.
     *
     * <p>The only structural difference from {@link #approve} is that nothing is
     * stamped afterwards and nothing is emailed. There is no application to stamp,
     * and there is deliberately no email: the ops user who typed this is, by
     * definition, already talking to the business (that is how they got the phone
     * number), and the password has to reach them by that same conversation
     * anyway - see {@code AccountEmails}' class doc for why it will never travel by
     * email. An unsolicited "your account is ready" to somebody who has not asked
     * for one is also the kind of mail that earns a complaint, and complaints are
     * scored against a sending domain every tenant shares.
     *
     * <p>{@code email} may be null here, which is the entire reason
     * {@code chk_clients_company_has_contact_email} was relaxed to cover COMPANY
     * only. See {@code CreateVendorRequest} for what that costs the vendor.
     */
    @Transactional
    public SuperAdminVendorDetail createVendor(CreateVendorRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new PasswordMismatchException();
        }

        VendorAccount account = createVendorAccount(new VendorSpec(
                request.name(),
                request.clientIdentifier(),
                normalize(request.email()),
                request.contactPhone(),
                normalize(request.addressLine1()),
                normalize(request.addressLine2()),
                normalize(request.city()),
                normalize(request.state()),
                request.commissionRate(),
                request.username(),
                request.password()));

        return toDetail(account.client());
    }

    /**
     * Edits a vendor's business metadata. Replace semantics on every field, so a
     * value the ops user cleared is written as NULL rather than silently kept -
     * see {@code UpdateVendorRequest}, and {@code SuperAdminClientService.update}
     * for the same rule stated on the tenant surface.
     *
     * <p>{@code findVendorOrThrow} is what stops this being a second, unlabelled
     * way to edit any client on the platform: an id belonging to a buying company
     * reads as "vendor not found" rather than being edited under a vendor heading.
     */
    @Transactional
    public SuperAdminVendorDetail updateVendor(UUID vendorId, UpdateVendorRequest request) {
        Client vendor = findVendorOrThrow(vendorId);

        vendor.setName(request.name().trim());
        vendor.setAdminContactEmail(normalize(request.email()));
        vendor.setPhone(normalize(request.contactPhone()));
        vendor.setAddressLine1(normalize(request.addressLine1()));
        vendor.setAddressLine2(normalize(request.addressLine2()));
        vendor.setCity(normalize(request.city()));
        vendor.setState(normalize(request.state()));
        vendor.setLogoUrl(normalize(request.logoUrl()));
        vendor.setCommissionRate(request.commissionRate());

        return toDetail(clientRepository.saveAndFlush(vendor));
    }

    /**
     * Sets a new password on a vendor's ONE account. The narrowest possible answer
     * to VENDOR_RESEARCH.md Section C item 9, and the decision
     * {@code SuperAdminVendorController} said this module would have to make.
     *
     * <h2>The gap this closes</h2>
     * A vendor has exactly one user, cannot create staff, and there is no
     * self-service password reset anywhere in this codebase. So one lost password
     * ends that business's ability to trade: they cannot log in, nobody at their
     * company can let them in, and until now nobody at ProcurePal could either -
     * {@code SuperAdminUserService}'s write half reaches ProcurePal's tenant and no
     * other, deliberately, and {@code SuperAdminTenantUserController} is read-only
     * by design. The only remaining fix was hand-written SQL against production.
     *
     * <h2>Why this is not the broad capability those two classes refuse to build</h2>
     * Their objection is specific and it still stands: a super admin who can write
     * ANY tenant's user rows holds a silent account-takeover capability over every
     * customer's inventory, orders and price list, bounded by nothing. This method
     * is bounded by three things at once.
     * <ul>
     *   <li>{@link #findVendorOrThrow} - the id must be a {@code client_type =
     *       'VENDOR'} row, so a buying company's id is a 404 here and there is no
     *       parameter that widens it.</li>
     *   <li>It targets the vendor's single account, resolved server-side as the
     *       client's one user, not a user id from the caller. There is nothing to
     *       point at somebody else.</li>
     *   <li>The account is one this same class created, for a business ProcurePal
     *       approved and takes a commission from. The platform already suspends,
     *       delists and moderates it; being able to restore its login is smaller
     *       than powers the operator has anyway.</li>
     * </ul>
     * The buying-company case is untouched: a customer whose owner is locked out is
     * still served by that customer's own OWNER, because they have colleagues. A
     * vendor has nobody, which is the whole difference.
     *
     * <h2>What is deliberately still not built</h2>
     * Account-owner transfer and impersonation. Both were on item 9's list; neither
     * is needed to get a locked-out vendor trading again, and both are materially
     * larger powers - transfer changes who the business IS on the platform, and
     * impersonation lets an operator act AS them, which no audit trail here could
     * make honest. They stay out until somebody asks for them with a use case.
     *
     * <p>Nothing here logs, returns or reproduces the plaintext, and the endpoint
     * answers 204 with no body. The account holder is emailed that it happened -
     * that mail is the only check on this power the vendor has, so it is not
     * optional, and {@code EmailRecipients} simply sends nothing when the vendor has
     * no address on file rather than failing the reset.
     */
    @Transactional
    public void resetVendorAccountPassword(UUID vendorId, ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.confirmNewPassword())) {
            throw new PasswordMismatchException();
        }
        Client vendor = findVendorOrThrow(vendorId);
        UUID clientId = vendor.getId();

        tenantScopeExecutor.runAs(clientId, () -> {
            // "The first of its users", the same way toDetail reads it and for the
            // same reason: the one-account rule is enforced on the write paths, so a
            // recovery endpoint that threw on a second row would fail exactly when
            // somebody most needed it. UserNotFoundException covers the real case -
            // a vendor client whose user row is missing entirely, which is a broken
            // account rather than a bad request.
            User account = userRepository.findAllByClientId(clientId).stream()
                    .findFirst()
                    .orElseThrow(UserNotFoundException::new);
            account.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            userRepository.saveAndFlush(account);
            emailNotificationService.passwordResetByAdmin(account);
        });
    }

    // ------------------------------------------------------------------------
    // Shared internals.
    // ------------------------------------------------------------------------

    /**
     * Everything needed to bring a vendor into existence, gathered from whichever
     * of the two paths is asking.
     *
     * <p>A record rather than an eleven-argument private method, because eleven
     * positional arguments of which seven are String is where a city ends up in a
     * state column and nothing complains. The two call sites read as a list of
     * labelled fields, which is the point.
     */
    private record VendorSpec(
            String name,
            String clientIdentifier,
            String email,
            String contactPhone,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            BigDecimal commissionRate,
            String username,
            String rawPassword) {
    }

    /** The two rows a vendor is, returned together so callers need no second lookup. */
    private record VendorAccount(Client client, User user) {
    }

    /**
     * The shared creation path both {@link #approve} and {@link #createVendor} run
     * through, so a vendor is the same shape however it came about.
     *
     * <h2>Why the user insert is duplicated from ClientSignupService rather than shared</h2>
     * {@code ClientProvisioning} takes the two pieces that would drift silently -
     * slug derivation and the default branch - and deliberately leaves this. See its
     * class doc for the full argument; the short version is that almost every field
     * of the user differs between the two flows (VENDOR against OWNER, a nullable
     * email against a required one, {@code TenantScopeExecutor.callAs} against
     * {@code TenantContext.set} in a finally, no login response against one), and a
     * shared method taking a parameter for each would be a builder with a different
     * name that hides which flow does what. Password hashing is not duplicated in
     * any meaningful sense: both call {@code encode} on the one
     * {@code PasswordEncoder} bean {@code SecurityConfig} defines, so they agree by
     * construction rather than by convention.
     *
     * <h2>root(true), and why no request can ask for it</h2>
     * A vendor's single user IS its account holder, so it is root - the same
     * condition the other three root-creating paths share (a client with no users
     * yet needs one, and only privileged server-side code can say who). What has not
     * changed is that root is derived here, never read from a body: neither
     * {@code CreateVendorRequest} nor {@code ApproveVendorApplicationRequest} has
     * the component, exactly as {@code CreateUserRequest} does not. It also matters
     * for a second reason unique to vendors - root cannot be deactivated or demoted
     * ({@code RootUserDeactivationNotAllowedException}), so a vendor's one account
     * cannot be turned into a non-account by anybody, including itself.
     */
    private VendorAccount createVendorAccount(VendorSpec spec) {
        String slug = clientProvisioning.requireAvailableSlug(spec.clientIdentifier(), spec.name());

        Role vendorRole = roleRepository
                .findByName(TenantRoles.VENDOR)
                .orElseThrow(() -> new IllegalStateException("VENDOR role not seeded - run the Flyway migrations"));

        Client vendor = clientRepository.saveAndFlush(Client.builder()
                .name(spec.name().trim())
                .slug(slug)
                .adminContactEmail(spec.email())
                .phone(spec.contactPhone().trim())
                .clientType(ClientType.VENDOR)
                .addressLine1(spec.addressLine1())
                .addressLine2(spec.addressLine2())
                .city(spec.city())
                .state(spec.state())
                .commissionRate(spec.commissionRate())
                // Not settable from any request body, on this surface or any other:
                // a vendor is not the marketplace operator, and platformOwner is a
                // bootstrap concern with no REST verb by design (see
                // UpdateClientRequest). Left to the builder's default rather than
                // set to false explicitly, so that a future default change is a
                // decision made in one place.
                .active(true)
                .build());

        // The legitimate first-and-only account. Asserted rather than assumed: this
        // method is the ONE path allowed to create a VENDOR-role user, so it is also
        // the one that would silently create a second if it were ever called against
        // an existing vendor (a retried approval, a future "re-provision" action). The
        // client above was created microseconds ago and has no users, so this passes -
        // and keeps passing only for as long as that stays true.
        vendorSingleAccountRule.assertMayAddUser(vendor.getId());

        User user = tenantScopeExecutor.callAs(vendor.getId(), () -> {
            clientProvisioning.createDefaultBranch();
            return userRepository.saveAndFlush(User.builder()
                    .username(spec.username().trim())
                    .passwordHash(passwordEncoder.encode(spec.rawPassword()))
                    .role(vendorRole)
                    .active(true)
                    .root(true)
                    // The same address as the client's contact of record, and null
                    // when there is none. Not a synthetic placeholder: EmailRecipients
                    // reads this column, so an invented address is an address we then
                    // send real mail to.
                    .email(spec.email())
                    .build());
        });

        return new VendorAccount(vendor, user);
    }

    private VendorWaitlistApplication findApplicationOrThrow(UUID applicationId) {
        return applicationRepository.findById(applicationId).orElseThrow(VendorApplicationNotFoundException::new);
    }

    /**
     * findByIdAndClientType, not findById: an id belonging to a buying company must
     * read as "not found" rather than be served under a vendor heading. See
     * {@link VendorNotFoundException}.
     */
    private Client findVendorOrThrow(UUID vendorId) {
        return clientRepository
                .findByIdAndClientType(vendorId, ClientType.VENDOR)
                .orElseThrow(VendorNotFoundException::new);
    }

    private static void requirePending(VendorWaitlistApplication application) {
        if (!application.isPending()) {
            throw new VendorApplicationAlreadyReviewedException(application.getStatus());
        }
    }

    private SuperAdminVendorSummary toSummary(Client vendor) {
        // "The first of its users", same reasoning as toDetail: the one-account rule is
        // enforced by the VENDOR role, not a constraint, so this must not throw on a second row.
        Optional<User> account = userRepository.findAllByClientId(vendor.getId()).stream().findFirst();

        return new SuperAdminVendorSummary(
                vendor.getId(),
                vendor.getName(),
                vendor.getSlug(),
                vendor.isActive(),
                vendor.getAdminContactEmail(),
                vendor.getPhone(),
                account.map(User::getUsername).orElse(null),
                vendor.getCommissionRate(),
                userRepository.countByClientId(vendor.getId()),
                productRepository.countByClientId(vendor.getId()),
                applicationRepository.findByApprovedClientId(vendor.getId()).isPresent(),
                vendor.getCreatedAt());
    }

    /**
     * The vendor's single user is read as "the first of its users" rather than
     * through a finder that assumes there is exactly one. The rule is enforced by
     * the VENDOR role not holding MANAGE_USERS, not by a database constraint, so a
     * detail screen that threw on a second row would turn a data anomaly into an
     * outage on the very screen somebody would use to investigate it. The summary's
     * {@code userCount} is where an anomaly shows up.
     */
    private SuperAdminVendorDetail toDetail(Client vendor) {
        Optional<User> account = userRepository.findAllByClientId(vendor.getId()).stream().findFirst();
        Optional<VendorWaitlistApplication> application =
                applicationRepository.findByApprovedClientId(vendor.getId());

        return new SuperAdminVendorDetail(
                vendor.getId(),
                vendor.getName(),
                vendor.getSlug(),
                vendor.isActive(),
                vendor.getAdminContactEmail(),
                vendor.getPhone(),
                vendor.getAddressLine1(),
                vendor.getAddressLine2(),
                vendor.getCity(),
                vendor.getState(),
                vendor.getLogoUrl(),
                vendor.getCommissionRate(),
                account.map(User::getId).orElse(null),
                account.map(User::getUsername).orElse(null),
                productRepository.countByClientId(vendor.getId()),
                application.map(VendorWaitlistApplication::getId).orElse(null),
                application.map(VendorWaitlistApplication::getCreatedAt).orElse(null),
                application.map(VendorWaitlistApplication::getReviewedAt).orElse(null),
                vendor.getCreatedAt(),
                vendor.getUpdatedAt());
    }

    /** Blank is how a form says "empty"; the database should say NULL. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
