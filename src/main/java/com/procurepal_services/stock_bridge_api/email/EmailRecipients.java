package com.procurepal_services.stock_bridge_api.email;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Works out who gets a given email. Kept apart from the templates because
 * "what does this say" and "who should see it" change for different reasons - a
 * copy edit should not be able to change an audience.
 *
 * <h2>Why this reads across tenants without a scope executor</h2>
 * Both lookups here are by primary key.
 * {@link com.procurepal_services.stock_bridge_api.entity.Client} is not a
 * tenant-scoped entity at all, and a {@code findById} on {@link User} goes through
 * {@code EntityManager.find}, which Hibernate's {@code @Filter} does not apply to -
 * the same property OrderResponseAssembler relies on to show ProcurePal who placed
 * a buyer's order. So no filter has to be moved and none is being evaded: the ids
 * come from rows the caller already loaded and authorised, never from a request.
 *
 * <p>The deliberate consequence is that this class never <em>lists</em> a
 * company's users. Broadcasting to every user of a tenant would need
 * {@code TenantScopeExecutor} - and, more to the point, would mail a ten-person
 * company ten copies of every status change on every order, which is how a useful
 * notification becomes a filter rule.
 *
 * <h2>Why no method here is @Transactional</h2>
 * This is load-bearing, not an omission. Every method runs inside a caller's
 * transaction - an order being placed, a payment being applied - and annotating
 * them would put a Spring transaction boundary between that caller and this code.
 * An exception crossing such a boundary marks the whole transaction
 * <em>rollback-only</em>, and that mark is permanent: {@link EmailDispatcher} would
 * still dutifully catch the exception, the business method would still run to
 * completion, and the commit would then fail with {@code UnexpectedRollbackException}
 * - turning a bad email address into a failed payment.
 *
 * <p>That is not hypothetical; it is exactly what a null {@code placed_by} did here
 * before this annotation was removed. With no boundary of its own, an exception in
 * this class is an ordinary exception the dispatcher can genuinely absorb, which is
 * the guarantee the whole email package is built on. Reads still work: Spring Data
 * repositories carry their own transaction, and these all join the caller's anyway.
 *
 * <h2>Who actually gets order mail</h2>
 * The company's admin contact, plus the person who placed the order if they are
 * someone else and have an address on file. That is the smallest set that reaches
 * both "the company" and "the human waiting for this delivery".
 */
@Component
@RequiredArgsConstructor
public class EmailRecipients {

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final SuperAdminRepository superAdminRepository;
    private final EmailProperties emailProperties;

    /**
     * The buyer side of an order. Empty is a normal outcome - a client row always
     * has an admin contact email (the column is NOT NULL), but nothing stops it
     * being an address that {@link EmailMessage} rejects as implausible.
     */
    public List<String> forOrderBuyer(Order order) {
        List<String> addresses = new ArrayList<>(forClient(order.getClientId()));
        // placed_by is nullable, and the null case is a real one rather than a
        // defensive flourish: an order placed by a Monnify webhook has no acting
        // user at all. findById(null) throws, so this guard is what keeps a
        // perfectly ordinary order from raising an exception on the way to a
        // courtesy email.
        if (order.getPlacedBy() != null) {
            userRepository.findById(order.getPlacedBy())
                    .map(User::getEmail)
                    .ifPresent(addresses::add);
        }
        return addresses;
    }

    /**
     * ProcurePal ops. The platform owner's own admin contact is always included, so
     * operator mail has a destination even on a deploy that never set
     * {@code app.email.operator-address}; the configured address is an addition for
     * teams who would rather this reached a shared alias than one person.
     *
     * <p>Empty when no platform owner has been bootstrapped, which is the same
     * "unseeded marketplace" state PlatformOwnerGuard.findPlatformOwner degrades to
     * rather than throwing.
     */
    public List<String> forOperator() {
        List<String> addresses = new ArrayList<>();
        clientRepository.findByPlatformOwnerTrue()
                .map(Client::getAdminContactEmail)
                .ifPresent(addresses::add);
        if (emailProperties.operatorAddress() != null && !emailProperties.operatorAddress().isBlank()) {
            addresses.add(emailProperties.operatorAddress());
        }
        return addresses;
    }

    /**
     * The inbox that researches new vendor applications - {@code
     * app.email.vendor-waitlist-address}, which always has a value (see
     * {@link EmailProperties}).
     *
     * <p>Deliberately NOT {@link #forOperator()} plus the alias. Operator mail is
     * about fulfilment - a new order, a payment received - and reaches whoever runs
     * that; a waitlist application is partner recruitment and reaches whoever does
     * that. They are one inbox at ProcurePaddy's current size and will not stay
     * that way, and a deploy that redirected this address would otherwise still be
     * copying the fulfilment team on every application, which is the opposite of
     * what "configurable" was asked for.
     *
     * <p>Never empty, unlike every other method here, which is why this one has no
     * degraded case to describe.
     */
    public List<String> forVendorWaitlist() {
        return List.of(emailProperties.vendorWaitlistAddress());
    }

    /**
     * Every platform operator - the {@code super_admins} table, in full.
     *
     * <h2>Why this one DOES list a table, when the class doc says it never does</h2>
     * The rule above ("this class never lists a company's users") is about tenants,
     * and its two reasons both fail to apply here. There is no tenant filter to move,
     * because {@link SuperAdmin} is not a tenant-scoped entity at all - the same
     * property that lets {@link #forClient} read a client by id. And the volume
     * argument does not carry: the objection to broadcasting is mailing a ten-person
     * company ten copies of every order update, whereas this list is a handful of
     * operators and the only thing addressed to it is a policy change that happens a
     * few times a year. Broadcasting is also the actual requirement - the owner asked
     * for the OTHER super admins to be told, not just the one who acted, because a
     * notice only the actor receives warns nobody.
     *
     * <h2>The address is the username, and the fallback is not decoration</h2>
     * {@code super_admins} has no email column - it has {@code username}, which in
     * every real deployment is an address, exactly as a tenant's first user's is (see
     * {@link #forUser}, which relies on the same fact for the same reason).
     * {@link EmailMessage} discards anything that is not plausibly an address, so a
     * deploy whose operators are called {@code admin} simply contributes nothing here
     * rather than handing SES something it will reject for everybody.
     *
     * <p>Which is why the operator alias is appended. If no super admin's username
     * looks like an address - a perfectly ordinary state for a small deploy - this
     * would otherwise return a list that {@link EmailMessage} empties, and a security
     * notice about a money rule would be silently dropped. The alias is the
     * configured inbox ProcurePal already trusts with operator mail, and reaching it
     * is strictly better than reaching nobody. It is appended rather than
     * substituted, and duplicates are collapsed by {@link EmailMessage} anyway.
     *
     * <h2>What this is NOT</h2>
     * Not {@link #forOperator()}. That one is ProcurePal's FULFILMENT inbox - the
     * platform owner's business contact plus the alias - and it reaches whoever
     * processes orders. This reaches the humans who hold super admin credentials,
     * which is a different set of people the moment the company is bigger than one
     * room. Sending settlement-policy mail to the fulfilment inbox would tell the
     * wrong team and, worse, would keep telling them after somebody redirected it.
     */
    public List<String> forSuperAdmins() {
        List<String> addresses = new ArrayList<>();
        for (SuperAdmin admin : superAdminRepository.findAll()) {
            addresses.add(admin.getUsername());
        }
        if (emailProperties.operatorAddress() != null && !emailProperties.operatorAddress().isBlank()) {
            addresses.add(emailProperties.operatorAddress());
        }
        return addresses;
    }

    /** A company's registered contact - the address ProcurePal has for the business itself. */
    public List<String> forClient(UUID clientId) {
        if (clientId == null) {
            return List.of();
        }
        return clientRepository.findById(clientId)
                .map(Client::getAdminContactEmail)
                .map(List::of)
                .orElseGet(List::of);
    }

    /**
     * One named person. Falls back to the username when no contact email is set,
     * because a tenant's first user signs up with an email AS their username and
     * would otherwise be unreachable - but only when that username actually looks
     * like an address, since sub-user usernames need not be one.
     * {@link EmailMessage} discards it if it is not.
     */
    public List<String> forUser(User user) {
        if (user == null) {
            return List.of();
        }
        String email = user.getEmail();
        if (email != null && !email.isBlank()) {
            return List.of(email);
        }
        return Optional.ofNullable(user.getUsername()).map(List::of).orElseGet(List::of);
    }
}
