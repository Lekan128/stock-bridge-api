package com.procurepal_services.stock_bridge_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportRowResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.PatchRowRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ValueMappingRequest;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Who may reach an import, and which one.
 *
 * <h2>Tenant isolation, asserted per endpoint rather than once</h2>
 * There are thirteen routes under {@code /api/imports} and each reaches the row through its own
 * finder. {@code BulkImportSessionIntegrationTest} checks one of them - {@code GET /{id}} - which
 * proves the finder that route happens to use is scoped and proves nothing at all about the other
 * twelve. A single unscoped {@code findById} anywhere in that set is a complete cross-tenant
 * read/write, so the sweep below walks every route.
 *
 * <p>{@code GET /{id}/rows} and the two row PATCHes are the sharpest of them.
 * {@code import_session_rows} carries no {@code client_id} of its own - M1 left it that way
 * deliberately and left the row repository without a by-id-alone finder to match, so "load the
 * file tenant-scoped, then find the line within it" IS the tenancy check. That makes those three
 * routes the only place in the feature where tenancy rests on a call convention rather than on a
 * predicate, which is exactly why they are tested with a real foreign row id and not only with a
 * foreign file id.
 *
 * <h2>Permissions, per kind</h2>
 * Contract section 3 splits the authority by the kind of import, not by the route, because
 * {@code GET /{id}} cannot tell them apart from its URL. A storekeeper - MANAGE_INVENTORY and no
 * MANAGE_PRODUCTS - is the case that matters: bulk stock-in is the job they do every week, and
 * the catalog is the thing they must not be able to rewrite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class BulkImportAccessControlIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final String CATALOG_HEADERS =
            "name,sku,description,cost_price,quantity_on_hand,low_stock_threshold,unit_of_measure,"
                    + "packaging_unit,packaging_size,vendor_name,vendor_sku,is_preferred_vendor\n";

    private static final String STOCK_IN_HEADERS =
            "sku,product_name,vendor_name,quantity,unit,unit_cost,packaging_size,received_date,reference\n";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RoleRepository roleRepository;

    // ------------------------------------------------------------ tenant isolation

    /**
     * Every read route, walked with the wrong tenant's token.
     *
     * <p>404 rather than 403 throughout: a 403 confirms the id exists, which is the one thing a
     * probe of the linkable {@code /app/products/import/:sessionId} URL is after.
     */
    @Test
    void anotherCompanyCannotReadAnyPartOfAnImportItDoesNotOwn() {
        TenantLoginResponse owner = signup("Isolation Owner Co");
        TenantLoginResponse stranger = signup("Isolation Stranger Co");

        ImportSessionResponse session = upload(
                owner, CATALOG_HEADERS + "Rice 50kg,ISO-1,,42000,10,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        commit(owner, session.id());
        String base = "/api/imports/" + session.id();

        assertNotFound(stranger, HttpMethod.GET, base);
        assertNotFound(stranger, HttpMethod.GET, base + "/rows?status=ALL");
        assertNotFound(stranger, HttpMethod.GET, base + "/preview");
        assertNotFound(stranger, HttpMethod.GET, base + "/result");
        assertNotFound(stranger, HttpMethod.GET, base + "/report");

        // And it never appears in their own list, which is the read route with no id in it at all.
        assertThat(list(stranger)).extracting(SummaryBody::id).doesNotContain(session.id().toString());
        assertThat(list(owner)).extracting(SummaryBody::id).contains(session.id().toString());
    }

    /** Every write route, walked with the wrong tenant's token. */
    @Test
    void anotherCompanyCannotChangeCommitUndoOrDiscardAnImportItDoesNotOwn() {
        TenantLoginResponse owner = signup("Isolation Write Owner Co");
        TenantLoginResponse stranger = signup("Isolation Write Stranger Co");

        ImportSessionResponse draft = upload(
                owner, CATALOG_HEADERS + "Rice 50kg,ISOW-1,,42000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        UUID rowId = rows(owner, draft.id()).get(0).id();
        String base = "/api/imports/" + draft.id();

        assertNotFound(stranger, HttpMethod.PATCH, base + "/rows/" + rowId, Map.of("normalized", Map.of("name", "x")));
        assertNotFound(stranger, HttpMethod.PATCH, base + "/rows/" + rowId + "/skip", Map.of("skipped", true));
        assertNotFound(stranger, HttpMethod.PATCH, base + "/mapping", Map.of("columnMapping", Map.of("sku", "sku")));
        assertNotFound(
                stranger,
                HttpMethod.PATCH,
                base + "/value-mappings",
                new ValueMappingRequest("vendor_name", "Anyone", new ValueResolution(ValueResolution.KIND_BLANK, null, null, null)));
        assertNotFound(stranger, HttpMethod.POST, base + "/commit", null);
        assertNotFound(stranger, HttpMethod.DELETE, base, null);

        // Untouched by any of it: the owner's file is still theirs, still uncommitted, still there.
        ImportSessionResponse afterwards = restTemplate
                .exchange(base, HttpMethod.GET, new HttpEntity<>(authHeaders(owner)), ImportSessionResponse.class)
                .getBody();
        assertThat(afterwards.status().name()).isEqualTo("READY");
        assertThat(rows(owner, draft.id()).get(0).normalized()).containsEntry("name", "Rice 50kg");

        // Undo needs a committed file to be a meaningful 404 rather than a 409.
        ImportSessionResponse committed = upload(
                owner, CATALOG_HEADERS + "Beans 100kg,ISOW-2,,52000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        commit(owner, committed.id());
        assertNotFound(stranger, HttpMethod.POST, "/api/imports/" + committed.id() + "/undo", null);
    }

    /**
     * The one that a per-file check alone would miss.
     *
     * <p>{@code import_session_rows} has no {@code client_id}. The stranger here holds a
     * legitimate file of their own and a legitimate row id belonging to somebody else, and asks
     * their own file to patch it. Only {@code findByIdAndSessionId} stands between that request
     * and a cross-tenant write; a {@code findById} on the row repository would let it through
     * while every file-level check in the codebase still passed.
     */
    @Test
    void aRowIdFromAnotherCompanysImportCannotBePatchedThroughYourOwn() {
        TenantLoginResponse owner = signup("Row Isolation Owner Co");
        TenantLoginResponse stranger = signup("Row Isolation Stranger Co");

        ImportSessionResponse theirs = upload(
                owner, CATALOG_HEADERS + "Rice 50kg,RISO-1,,42000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        UUID foreignRowId = rows(owner, theirs.id()).get(0).id();

        ImportSessionResponse mine = upload(
                stranger, CATALOG_HEADERS + "Beans 100kg,RISO-2,,52000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");

        assertNotFound(
                stranger,
                HttpMethod.PATCH,
                "/api/imports/" + mine.id() + "/rows/" + foreignRowId,
                Map.of("normalized", Map.of("name", "Stolen")));
        assertNotFound(
                stranger,
                HttpMethod.PATCH,
                "/api/imports/" + mine.id() + "/rows/" + foreignRowId + "/skip",
                Map.of("skipped", true));

        // The victim's row is exactly as they left it.
        ImportRowResponse victim = rows(owner, theirs.id()).get(0);
        assertThat(victim.normalized()).containsEntry("name", "Rice 50kg");
        assertThat(victim.status().name()).isNotEqualTo("SKIPPED");
    }

    // ---------------------------------------------------------------- permissions

    /**
     * The storekeeper's whole job, and the one thing outside it. Contract section 3's split
     * exists for this person: MANAGE_INVENTORY, no MANAGE_PRODUCTS.
     */
    @Test
    void aStorekeeperCanRunAStockInImportAndCannotRunACatalogImport() {
        TenantLoginResponse owner = signup("Storekeeper Co");
        TenantLoginResponse storekeeper = userWithRole(owner, "STOREKEEPER");
        commitCatalog(owner, CATALOG_HEADERS + "Rice 50kg,SK-1,,42000,,,KG,,,,,\n");

        ImportSessionResponse delivery = upload(
                storekeeper, STOCK_IN_HEADERS + "SK-1,Rice 50kg,,25,KG,42000,,,WB-1\n", "STOCK_IN", null);
        assertThat(delivery.status().name()).isEqualTo("READY");
        assertThat(commitRaw(storekeeper, delivery.id()).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(uploadRaw(
                                storekeeper,
                                CATALOG_HEADERS + "Salt 25kg,SK-2,,12000,,,KG,,,,,\n",
                                "PRODUCT_CATALOG",
                                "CREATE_ONLY")
                        .getStatusCode())
                .as("rewriting the catalog is not a storekeeper's job")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The half of the check the controller cannot make. {@code GET /api/imports/{id}} does not say
     * from its URL which kind of import it is, so the coarse {@code MANAGE_PRODUCTS or
     * MANAGE_INVENTORY} at the method lets the storekeeper through and the service has to turn
     * them away against the loaded row. If that second check were missing, a storekeeper could
     * read, repair and commit a colleague's catalog import through a link they were sent.
     */
    @Test
    void aStorekeeperIsTurnedAwayFromACatalogImportTheirColleagueUploaded() {
        TenantLoginResponse owner = signup("Kind Check Co");
        TenantLoginResponse storekeeper = userWithRole(owner, "STOREKEEPER");
        ImportSessionResponse catalog = upload(
                owner, CATALOG_HEADERS + "Rice 50kg,KC-1,,42000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        String base = "/api/imports/" + catalog.id();

        assertForbidden(storekeeper, HttpMethod.GET, base);
        assertForbidden(storekeeper, HttpMethod.GET, base + "/rows?status=ALL");
        assertForbidden(storekeeper, HttpMethod.GET, base + "/preview");
        assertForbidden(storekeeper, HttpMethod.POST, base + "/commit", null);
        assertForbidden(storekeeper, HttpMethod.DELETE, base, null);
    }

    /** The two template downloads carry the same split, one authority each. */
    @Test
    void theTwoTemplatesAreGatedOnTheAuthorityTheirImportNeeds() {
        TenantLoginResponse owner = signup("Template Permission Co");
        TenantLoginResponse storekeeper = userWithRole(owner, "STOREKEEPER");

        assertThat(status(storekeeper, HttpMethod.GET, "/api/imports/templates/stock-in"))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(storekeeper, HttpMethod.GET, "/api/imports/templates/products"))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(owner, HttpMethod.GET, "/api/imports/templates/products")).isEqualTo(HttpStatus.OK);
    }

    /** Somebody with neither authority never reaches the module at all. */
    @Test
    void someoneWithNeitherImportAuthorityIsRefusedAtTheDoor() {
        TenantLoginResponse owner = signup("No Import Authority Co");
        TenantLoginResponse finance = userWithRole(owner, "FINANCE_OFFICER");

        assertThat(status(finance, HttpMethod.GET, "/api/imports")).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(uploadRaw(finance, CATALOG_HEADERS + "Rice 50kg,NIA-1,,42000,,,KG,,,,,\n", "PRODUCT_CATALOG", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(uploadRaw(finance, STOCK_IN_HEADERS + "NIA-1,Rice,,5,KG,,,,\n", "STOCK_IN", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Contract section 3: inline vendor creation needs MANAGE_VENDORS <em>on top of</em> the
     * import authority, and inline product creation needs MANAGE_PRODUCTS. A storekeeper has
     * neither.
     *
     * <p>The resolution card correctly hides both offers - {@code allowCreateNew} is false - but
     * a hidden button is a display decision, not a permission. This asserts the request itself is
     * refused, because {@code PATCH /value-mappings} is an ordinary authenticated call that
     * anybody who can open the review screen can make by hand.
     */
    @Test
    void aStorekeeperCanNeitherBeOfferedNorForceInlineCreationOfASupplierOrAProduct() {
        TenantLoginResponse owner = signup("Inline Creation Co");
        TenantLoginResponse storekeeper = userWithRole(owner, "STOREKEEPER");
        commitCatalog(owner, CATALOG_HEADERS + "Rice 50kg,INL-1,,42000,,,KG,,,,,\n");

        ImportSessionResponse session = upload(
                storekeeper,
                STOCK_IN_HEADERS + "INL-1,Rice 50kg,Brand New Supplier Ltd,25,KG,42000,,,WB-1\n"
                        + "INL-UNKNOWN,Mystery Item,,5,KG,1000,,,WB-2\n",
                "STOCK_IN",
                null);

        assertThat(session.unresolvedValues())
                .as("both questions are asked, and neither offers a create option to this user")
                .isNotEmpty()
                .allSatisfy(unresolved -> assertThat(unresolved.allowCreateNew()).isFalse());

        assertThat(resolveStatus(
                        storekeeper,
                        session.id(),
                        new ValueMappingRequest(
                                "vendor_name",
                                "Brand New Supplier Ltd",
                                new ValueResolution(ValueResolution.KIND_CREATE_NEW, null, null, Map.of("name", "Brand New Supplier Ltd")))))
                .as("adding a supplier to the company directory needs MANAGE_VENDORS, hidden button or not")
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(resolveStatus(
                        storekeeper,
                        session.id(),
                        new ValueMappingRequest(
                                "sku",
                                "INL-UNKNOWN",
                                new ValueResolution(
                                        ValueResolution.KIND_CREATE_NEW,
                                        null,
                                        null,
                                        Map.of("name", "Mystery Item", "unitOfMeasure", "KG")))))
                .as("adding a product to the catalog needs MANAGE_PRODUCTS, hidden button or not")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** The same two answers, from a user who does hold the extra authority. */
    @Test
    void anOwnerHoldingTheExtraAuthoritiesCanStillResolveInlineCreation() {
        TenantLoginResponse owner = signup("Inline Creation Allowed Co");
        commitCatalog(owner, CATALOG_HEADERS + "Rice 50kg,INLA-1,,42000,,,KG,,,,,\n");

        ImportSessionResponse session = upload(
                owner,
                STOCK_IN_HEADERS + "INLA-1,Rice 50kg,Brand New Supplier Ltd,25,KG,42000,,,WB-1\n",
                "STOCK_IN",
                null);
        assertThat(session.unresolvedValues())
                .singleElement()
                .satisfies(unresolved -> assertThat(unresolved.allowCreateNew()).isTrue());

        assertThat(resolveStatus(
                        owner,
                        session.id(),
                        new ValueMappingRequest(
                                "vendor_name",
                                "Brand New Supplier Ltd",
                                new ValueResolution(ValueResolution.KIND_CREATE_NEW, null, null, Map.of("name", "Brand New Supplier Ltd")))))
                .isEqualTo(HttpStatus.OK);
    }

    // ----------------------------------------------------------------- helpers

    private void assertNotFound(TenantLoginResponse as, HttpMethod method, String path) {
        assertNotFound(as, method, path, null);
    }

    private void assertNotFound(TenantLoginResponse as, HttpMethod method, String path, Object body) {
        assertThat(status(as, method, path, body))
                .as("%s %s must answer 404 for another company - a 403 would confirm the id exists", method, path)
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void assertForbidden(TenantLoginResponse as, HttpMethod method, String path) {
        assertForbidden(as, method, path, null);
    }

    private void assertForbidden(TenantLoginResponse as, HttpMethod method, String path, Object body) {
        assertThat(status(as, method, path, body))
                .as("%s %s must answer 403 for a user without the authority this kind needs", method, path)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private HttpStatusCode status(TenantLoginResponse as, HttpMethod method, String path) {
        return status(as, method, path, null);
    }

    private HttpStatusCode status(TenantLoginResponse as, HttpMethod method, String path, Object body) {
        HttpHeaders headers = authHeaders(as);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return restTemplate
                .exchange(path, method, new HttpEntity<>(body, headers), String.class)
                .getStatusCode();
    }

    private HttpStatusCode resolveStatus(TenantLoginResponse as, UUID sessionId, ValueMappingRequest request) {
        return status(as, HttpMethod.PATCH, "/api/imports/" + sessionId + "/value-mappings", request);
    }

    private List<ImportRowResponse> rows(TenantLoginResponse tenant, UUID sessionId) {
        return restTemplate
                .exchange(
                        "/api/imports/" + sessionId + "/rows?status=ALL",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<TestPage<ImportRowResponse>>() {})
                .getBody()
                .content();
    }

    private List<SummaryBody> list(TenantLoginResponse tenant) {
        return restTemplate
                .exchange(
                        "/api/imports?size=100",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<TestPage<SummaryBody>>() {})
                .getBody()
                .content();
    }

    private ImportSessionResponse upload(TenantLoginResponse tenant, String csv, String kind, String mode) {
        ResponseEntity<ImportSessionResponse> response =
                restTemplate.exchange(
                        "/api/imports",
                        HttpMethod.POST,
                        uploadEntity(tenant, csv, kind, mode),
                        ImportSessionResponse.class);
        assertThat(response.getStatusCode()).as("upload must answer 201").isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /** Deliberately typed to String: a refused upload answers with an ApiError, not a session. */
    private ResponseEntity<String> uploadRaw(TenantLoginResponse tenant, String csv, String kind, String mode) {
        return restTemplate.exchange(
                "/api/imports", HttpMethod.POST, uploadEntity(tenant, csv, kind, mode), String.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> uploadEntity(
            TenantLoginResponse tenant, String csv, String kind, String mode) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "upload.csv";
            }
        };
        body.add("file", new HttpEntity<>(resource, new HttpHeaders()));
        body.add("kind", kind);
        if (mode != null) {
            body.add("mode", mode);
        }
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private void commitCatalog(TenantLoginResponse tenant, String csv) {
        commit(tenant, upload(tenant, csv, "PRODUCT_CATALOG", "CREATE_ONLY").id());
    }

    private void commit(TenantLoginResponse tenant, UUID sessionId) {
        assertThat(commitRaw(tenant, sessionId).getStatusCode())
                .as("commit must answer 200")
                .isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> commitRaw(TenantLoginResponse tenant, UUID sessionId) {
        return restTemplate.exchange(
                "/api/imports/" + sessionId + "/commit",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                String.class);
    }

    private TenantLoginResponse userWithRole(TenantLoginResponse owner, String role) {
        String username = role.toLowerCase() + "-" + UUID.randomUUID();
        restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(
                                username,
                                PASSWORD,
                                roleRepository.findByName(role).orElseThrow().getId(),
                                null,
                                null,
                                null,
                                null,
                                null),
                        authHeaders(owner)),
                UserSummaryResponse.class);
        return restTemplate.postForObject(
                "/api/auth/login",
                new LoginRequest(owner.user().clientIdentifier(), username, PASSWORD),
                TenantLoginResponse.class);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TestPage<T>(List<T> content, int totalElements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SummaryBody(String id, String status) {
    }
}
