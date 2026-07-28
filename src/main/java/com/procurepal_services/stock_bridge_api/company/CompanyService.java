package com.procurepal_services.stock_bridge_api.company;

import com.procurepal_services.stock_bridge_api.company.dto.CompanyResponse;
import com.procurepal_services.stock_bridge_api.company.dto.UpdateCompanyRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company-level counterpart to ProfileService: /api/me is "my account",
 * this is "my employer's account". Same shape of authorization - the caller
 * never names the thing they are acting on, so the only row these methods can
 * reach is their own.
 *
 * <h2>Why TenantContext and nothing else</h2>
 * Client is the one entity in this codebase that is NOT tenant-scoped: it has no
 * client_id column because it IS the tenant, so it carries no TenantAwareEntity
 * mapping and the Hibernate tenant filter does not touch it. Every other service
 * here gets isolation for free from that filter; this one does not, and
 * {@code clientRepository.findById} would happily hand over any tenant's row.
 *
 * The only defence is therefore the id itself, and it comes from
 * TenantContext.get() - set by TenantResolutionFilter from the authenticated
 * principal - and from nowhere else. There is deliberately no client id in any
 * path variable, query parameter or request body on this surface: accepting one
 * would turn a self-service settings form into a cross-tenant write, and no
 * amount of "but we check it matches" is as safe as never reading it. If a
 * future caller needs to edit somebody else's company, that is
 * /api/superadmin/clients, which authenticates a different audience entirely.
 *
 * <h2>Why the update is narrow</h2>
 * Only three columns are assigned below, and the request record has no way to
 * express any other. See UpdateCompanyRequest for what is excluded and what each
 * exclusion is protecting - in short: the platform-owner flag, payment terms,
 * suspension status and the login slug are all decisions made about a tenant,
 * not by one.
 */
@Service
@RequiredArgsConstructor
public class CompanyService {

    private final ClientRepository clientRepository;

    @Transactional(readOnly = true)
    public CompanyResponse get() {
        return CompanyResponse.from(requireCompany());
    }

    /**
     * Replaces the three editable fields wholesale (see UpdateCompanyRequest) - a
     * phone number the user cleared is written as NULL, not silently preserved.
     *
     * saveAndFlush rather than relying on the dirty-check at commit so the
     * response is built from a row the database has actually accepted; a length
     * or NOT NULL violation then surfaces here, inside the request, rather than
     * during the commit that happens after the response has been serialised.
     */
    @Transactional
    public CompanyResponse update(UpdateCompanyRequest request) {
        Client company = requireCompany();
        company.setName(request.name().trim());
        company.setAdminContactEmail(request.adminEmail().trim());
        company.setPhone(normalize(request.phone()));
        return CompanyResponse.from(clientRepository.saveAndFlush(company));
    }

    /**
     * An authenticated caller whose client row is missing is a broken invariant,
     * not a client error - users cascade-delete with their client, so a live token
     * for a deleted tenant should not exist. IllegalStateException (a 500) is the
     * honest answer; a 404 would suggest the caller asked for the wrong thing when
     * they cannot have asked for anything at all. Same reasoning ProfileService
     * uses for a user with no client.
     */
    private Client requireCompany() {
        UUID tenantId = requireTenantId();
        return clientRepository
                .findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant " + tenantId + " has no client row"));
    }

    /** Blank is how a form says "empty"; the database should say NULL. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
