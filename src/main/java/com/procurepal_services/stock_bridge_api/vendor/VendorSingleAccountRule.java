package com.procurepal_services.stock_bridge_api.vendor;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "A vendor has exactly ONE user account and cannot create staff", made into an
 * invariant instead of a gap in a permission set.
 *
 * <h2>What was there before, and why it was not enough</h2>
 * The rule was implemented entirely by an absence: the VENDOR role does not hold
 * MANAGE_USERS, so POST /api/users answers 403 and the Users nav item does not
 * render. That hides the capability. It does not make the rule true, and the
 * difference matters more here than it usually does - a second login to a vendor
 * is a second login to that business's entire presence on the marketplace, and
 * the account-holder protections (root cannot be demoted or deactivated) would
 * then cover only one of the two. Anything that widened the role, added an admin
 * path, or created a user for a client id it was handed would undo it silently.
 *
 * <p>So the rule is asserted here, called from every service that creates a
 * {@code users} row. There are five, and all five were audited:
 * {@code UserManagementService.create} (the tenant-facing one, and the only route
 * a vendor's own account could ever take), {@code SuperAdminUserService
 * .createPlatformOwnerUser}, {@code SuperAdminVendorService.createVendorAccount}
 * (the legitimate first account), {@code ClientSignupService} and
 * {@code PlatformOwnerBootstrapRunner}. The first three call this. The last two
 * create the client in the same breath as its root user and never set
 * {@code clientType}, so the client they write to is a COMPANY by construction and
 * there is no id from outside for them to be pointed at; they are covered by the
 * trigger and by that structure rather than by a call that could never fire.
 *
 * <p>It is backed by a database trigger
 * ({@code trg_users_vendor_single_account}, V13) for the routes nobody thought
 * of. The trigger is the guarantee; this class is what makes the refusal a clean,
 * explained 4xx rather than a constraint violation - see
 * {@link VendorSingleAccountException}.
 *
 * <h2>Why it counts rows rather than asking "is this a create"</h2>
 * The condition is "would this client end up with more than one user", not "is
 * somebody using the create endpoint". Counting is what makes the legitimate
 * creation path - {@code SuperAdminVendorService}, bringing a vendor into
 * existence with its first and only account - pass without an exemption flag that
 * a later caller could copy. There is no way to ask for the rule to be skipped,
 * which is the property worth having.
 *
 * <h2>Not a check on the caller</h2>
 * Nothing here reads TenantContext or the current principal. The question is
 * about the client being written to, whoever is asking, which is what lets the
 * same call sit in a tenant-facing service and a super-admin one and mean the
 * same thing in both.
 */
@Component
@RequiredArgsConstructor
public class VendorSingleAccountRule {

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;

    /**
     * Refuses if {@code clientId} names a vendor that already has a user.
     *
     * <p>Call this BEFORE building the user, not after - the point is that the
     * insert never happens, so the caller's transaction has nothing to unwind and
     * no half-written side effects (a verification token, a welcome email) to
     * regret.
     *
     * <p>A client id that does not exist passes. It is not this rule's job to
     * report that; the foreign key and the caller's own lookup already do, and
     * answering "vendors have one account" to somebody who typed a bad id would be
     * a misleading message.
     *
     * @throws VendorSingleAccountException if the client is a vendor with at least
     *     one user row - active or not. Deactivating the first account and adding a
     *     second is still two logins for a business entitled to one.
     */
    @Transactional(readOnly = true)
    public void assertMayAddUser(UUID clientId) {
        if (clientId == null) {
            return;
        }
        boolean isVendor = clientRepository.findById(clientId).map(Client::isVendor).orElse(false);
        if (isVendor && userRepository.countByClientId(clientId) > 0) {
            throw new VendorSingleAccountException();
        }
    }
}
