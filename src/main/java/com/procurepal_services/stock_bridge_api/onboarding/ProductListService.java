package com.procurepal_services.stock_bridge_api.onboarding;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.founding.SetupRequestAlerts;
import com.procurepal_services.stock_bridge_api.founding.SetupRequestService;
import com.procurepal_services.stock_bridge_api.founding.WhatsAppNumbers;
import com.procurepal_services.stock_bridge_api.onboarding.dto.ProductListFile;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * "Send us your list" (LANDING_PAGE_PLAN.md §4, the setup checklist's first item): a shop sends its
 * Excel, CSV, PDF or photos of its stock book, and the team loads its products within 24 hours.
 *
 * <p>One file per request, so a phone on a weak signal sends what it can and retries the rest; the
 * app shrinks photos before sending. The list joins the shop's setup request (one is booked for a
 * shop that signed up without the landing page), which moves to LIST_RECEIVED, and the team is told
 * once per batch: the first file after a quiet half hour.
 */
@Service
@RequiredArgsConstructor
public class ProductListService {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_FILES_PER_SHOP = 30;
    static final Set<String> EXTENSIONS =
            Set.of("xlsx", "xls", "csv", "ods", "pdf", "jpg", "jpeg", "png", "webp", "heic", "heif", "txt", "docx", "doc");

    private final NamedParameterJdbcTemplate jdbc;
    private final ClientRepository clientRepository;
    private final SetupRequestService setupRequestService;
    private final SetupRequestAlerts alerts;

    @Transactional
    public ProductListFile upload(UUID clientId, UUID userId, MultipartFile file) {
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "list" : file.getOriginalFilename().replaceAll("[\\\\/\\r\\n]", "_");
        if (name.length() > 255) {
            name = name.substring(name.length() - 255);
        }
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (file.isEmpty()) {
            throw new ProductListException("That file is empty.");
        }
        if (!EXTENSIONS.contains(extension)) {
            throw new ProductListException("Send an Excel or CSV file, a PDF, or photos (JPEG, PNG, HEIC).");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ProductListException("That file is over 5 MB. Send it on WhatsApp instead, or split it.");
        }
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM product_list_files WHERE client_id = :clientId", Map.of("clientId", clientId), Integer.class);
        if (existing != null && existing >= MAX_FILES_PER_SHOP) {
            throw new ProductListException("You've sent " + MAX_FILES_PER_SHOP + " files already. Send the rest on WhatsApp.");
        }

        Client client = clientRepository.findById(clientId).orElseThrow();
        // The number the team messages: the shop's, in +234 form, if it gave a mobile number.
        String whatsapp = client.getPhone() == null ? null : WhatsAppNumbers.normalise(client.getPhone()).orElse(null);
        SetupRequestService.LinkedRequest request = setupRequestService.requestForClient(clientId, client.getName(), whatsapp);

        Boolean quiet = jdbc.queryForObject(
                "SELECT NOT EXISTS (SELECT 1 FROM product_list_files WHERE client_id = :clientId"
                        + " AND created_at > now() - interval '30 minutes')",
                Map.of("clientId", clientId), Boolean.class);

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String contentType = file.getContentType() == null || file.getContentType().isBlank()
                ? "application/octet-stream" : file.getContentType();
        jdbc.update(
                "INSERT INTO product_list_files (id, client_id, setup_request_id, file_name, content_type, size_bytes, data, uploaded_by, created_at)"
                        + " VALUES (:id, :clientId, :requestId, :name, :type, :size, :data, :userId, :createdAt)",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("clientId", clientId)
                        .addValue("requestId", request.id())
                        .addValue("name", name)
                        .addValue("type", contentType.length() > 120 ? contentType.substring(0, 120) : contentType)
                        .addValue("size", data.length)
                        .addValue("data", data)
                        .addValue("userId", userId)
                        .addValue("createdAt", now));
        setupRequestService.markListReceived(request.id());
        if (Boolean.TRUE.equals(quiet)) {
            alerts.listReceived(client.getName(), client.getSlug(), whatsapp, 1);
        }
        return new ProductListFile(id, name, contentType, data.length, now);
    }

    @Transactional(readOnly = true)
    public List<ProductListFile> files(UUID clientId) {
        return jdbc.query(
                "SELECT id, file_name, content_type, size_bytes, created_at FROM product_list_files"
                        + " WHERE client_id = :clientId ORDER BY created_at",
                Map.of("clientId", clientId),
                (rs, rowNum) -> new ProductListFile(
                        rs.getObject("id", UUID.class),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getInt("size_bytes"),
                        rs.getObject("created_at", OffsetDateTime.class)));
    }

    /** One file's bytes, for the team to download. */
    @Transactional(readOnly = true)
    public StoredFile download(UUID clientId, UUID fileId) {
        List<StoredFile> found = jdbc.query(
                "SELECT file_name, content_type, data FROM product_list_files WHERE id = :id AND client_id = :clientId",
                Map.of("id", fileId, "clientId", clientId),
                (rs, rowNum) -> new StoredFile(rs.getString("file_name"), rs.getString("content_type"), rs.getBytes("data")));
        if (found.isEmpty()) {
            throw new ProductListException("That file does not exist.");
        }
        return found.getFirst();
    }

    public record StoredFile(String fileName, String contentType, byte[] data) {
    }
}
