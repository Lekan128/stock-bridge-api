package com.procurepal_services.stock_bridge_api.marketplace;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single answer to "whose products may appear on this marketplace".
 *
 * <h2>Why this exists rather than a call to ClientRepository at each site</h2>
 * "All sellers" is not one repository call and never will be. It is the platform
 * owner PLUS the active {@link ClientType#VENDOR} clients, because
 * {@code is_platform_owner} and {@code client_type} are orthogonal (V11 explains
 * why at length): ProcurePal is a {@code COMPANY} that owns the platform, so
 * {@code findAllByClientType(VENDOR)} does NOT include it. Every surface that
 * assembles the set by hand is one refactor away from dropping ProcurePal out of
 * its own storefront, or - far worse, and silently - from including a buying
 * company whose private inventory would then be published as marketplace
 * listings.
 *
 * <h2>The predicate this feeds, and why "just drop the filter" is not an option</h2>
 * The public catalog endpoints run with no {@code TenantContext} and therefore
 * with the Hibernate tenant filter switched off for the whole request (see
 * {@link PlatformOwnerGuard#findPlatformOwner()} and the PERMIT_ALL_PATHS comment
 * in SecurityConfig). Before vendors existed, one {@code client_id = <platform
 * owner>} predicate was the only thing standing between an anonymous GET and
 * every tenant's product table. Opening selling up widens that predicate from one
 * id to a set of ids; it does not remove it. Widening it to "no predicate at all"
 * would publish every buying company's stock list to the internet.
 *
 * <h2>Active, and the one deliberate asymmetry</h2>
 * Vendors must be {@code active} to sell - deactivating a vendor account is how an
 * operator pulls their listings without deleting anything. The platform owner is
 * included regardless of its {@code active} flag, which is exactly the behaviour
 * the storefront had before this class existed ({@code findByPlatformOwnerTrue()}
 * never checked it). That asymmetry is preserved on purpose rather than tidied
 * up: "ProcurePal is inactive" is not a state the product has any meaning for,
 * and making the entire storefront depend on a flag nobody sets would turn a
 * stray UPDATE into a total outage.
 *
 * <h2>What it deliberately does not do</h2>
 * It answers "may this client sell", never "is this caller allowed to do X". That
 * is {@link com.procurepal_services.stock_bridge_api.vendor.VendorGuard}'s job,
 * and it reads the CURRENT request's tenant. Nothing here is an authorization
 * check.
 */
@Component
@RequiredArgsConstructor
public class SellerDirectory {

    private final ClientRepository clientRepository;

    /**
     * Every client whose listed products belong on the storefront: the platform
     * owner first, then active vendors alphabetically.
     *
     * <p>Ordered rather than a bare Set because two callers publish it - the
     * storefront's seller filter and the vendor directory page - and an
     * unstable order there is a UI that reshuffles on every refresh. ProcurePal
     * leads because it is the marketplace's own inventory, not one vendor among
     * the alphabetised rest.
     */
    @Transactional(readOnly = true)
    public List<Client> activeSellers() {
        List<Client> sellers = new ArrayList<>();
        clientRepository.findByPlatformOwnerTrue().ifPresent(sellers::add);
        for (Client vendor : clientRepository.findAllByClientTypeAndActiveTrueOrderByNameAsc(ClientType.VENDOR)) {
            // Guard against the pathological row that is somehow both: adding it
            // twice would double every count built off this list.
            if (!vendor.isPlatformOwner()) {
                sellers.add(vendor);
            }
        }
        return sellers;
    }

    /**
     * The id set for the catalog's {@code client_id IN (...)} predicate.
     *
     * <p>Empty means an unseeded marketplace with no vendors, and callers must
     * degrade to an EMPTY CATALOG rather than to an unfiltered query. Getting that
     * backwards - treating "no sellers" as "no filter" - is the one mistake in
     * this class that leaks every tenant's inventory at once, so the emptiness is
     * returned honestly rather than papered over with a default.
     */
    @Transactional(readOnly = true)
    public Set<UUID> activeSellerIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Client seller : activeSellers()) {
            ids.add(seller.getId());
        }
        return ids;
    }

    /** An active seller by id, or empty if that client may not sell (or does not exist). */
    @Transactional(readOnly = true)
    public Optional<Client> findActiveSeller(UUID clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        return activeSellers().stream()
                .filter(seller -> seller.getId().equals(clientId))
                .findFirst();
    }

    /**
     * An active seller by id OR slug, for the storefront's per-seller pages -
     * which link by slug for the same SEO and shareability reasons product pages
     * do, while the catalog's seller filter carries the id.
     *
     * <p>Parse-then-branch rather than try-both, matching
     * {@code MarketplaceCatalogService.get}: a value that parses as a UUID is an
     * id, full stop, so a seller can never be shadowed by another whose slug
     * happens to look like a UUID.
     */
    @Transactional(readOnly = true)
    public Optional<Client> findActiveSeller(String idOrSlug) {
        if (idOrSlug == null || idOrSlug.isBlank()) {
            return Optional.empty();
        }
        try {
            return findActiveSeller(UUID.fromString(idOrSlug));
        } catch (IllegalArgumentException notAnId) {
            return activeSellers().stream()
                    .filter(seller -> idOrSlug.equals(seller.getSlug()))
                    .findFirst();
        }
    }

    /** Whether this client's listed products belong in the public catalog today. */
    @Transactional(readOnly = true)
    public boolean isActiveSeller(UUID clientId) {
        return findActiveSeller(clientId).isPresent();
    }

    /**
     * Sellers by id, for decorating a page of catalog rows or order lines with the
     * seller's name and logo without one query per row.
     *
     * <p>Reads the whole (small) seller list once and indexes it, rather than
     * issuing an {@code IN} query: the number of sellers is bounded by how many
     * businesses the operator has onboarded by hand, and this way there is exactly
     * one definition of who counts as a seller instead of two that can drift.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Client> activeSellersById() {
        Map<UUID, Client> byId = new LinkedHashMap<>();
        for (Client seller : activeSellers()) {
            byId.put(seller.getId(), seller);
        }
        return byId;
    }

    /**
     * A seller by id WITHOUT the active or type checks - for reading historical
     * facts about a party that has since been deactivated or delisted.
     *
     * <p>An order names the seller who sold it, forever. Suspending a vendor must
     * stop them selling; it must not blank out the seller's name on an invoice
     * from last March, or make a past order impossible to render. Never use this
     * to decide what may be listed - {@link #isActiveSeller} is that question.
     */
    @Transactional(readOnly = true)
    public Optional<Client> findSellerOfRecord(UUID clientId) {
        return clientId == null ? Optional.empty() : clientRepository.findById(clientId);
    }
}
