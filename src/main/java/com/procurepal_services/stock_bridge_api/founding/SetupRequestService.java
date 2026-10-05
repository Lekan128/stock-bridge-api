package com.procurepal_services.stock_bridge_api.founding;

import com.procurepal_services.stock_bridge_api.founding.dto.FoundingOfferStatus;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestRequest;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Founding setup requests: the lead the landing page captures before any account exists, and the
 * live numbers it shows beside the offer (LANDING_PAGE_PLAN.md, conversion rules 4 and 7).
 *
 * <p>Runs on a public endpoint with no principal, so no tenant filter applies; setup_requests is
 * deliberately not tenant-scoped (a lead has no company yet). Plain JDBC for that reason.
 */
@Slf4j
@Service
public class SetupRequestService {

    private final NamedParameterJdbcTemplate jdbc;
    private final FoundingOfferProperties properties;
    private final SetupRequestRateLimiter rateLimiter;
    private final Clock clock;

    @Autowired
    public SetupRequestService(NamedParameterJdbcTemplate jdbc, FoundingOfferProperties properties, SetupRequestRateLimiter rateLimiter) {
        this(jdbc, properties, rateLimiter, Clock.systemUTC());
    }

    SetupRequestService(NamedParameterJdbcTemplate jdbc, FoundingOfferProperties properties, SetupRequestRateLimiter rateLimiter, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** "63 of 100 left, 4 of 10 this week, ends 1 February 2027": counts, never typed in. */
    @Transactional(readOnly = true)
    public FoundingOfferStatus status() {
        int taken = foundingTaken();
        int left = Math.max(0, properties.total() - taken);
        return new FoundingOfferStatus(
                properties.total(),
                left,
                properties.weeklyCapacity(),
                foundingSince(weekStart(today())),
                properties.endsOn(),
                left > 0 && today().isBefore(properties.endsOn()));
    }

    @Transactional
    public SetupRequestResponse request(SetupRequestRequest request, String callerAddress) {
        String whatsapp = WhatsAppNumbers.normalise(request.whatsapp()).orElseThrow(InvalidWhatsAppNumberException::new);

        // A bot filled the field people never see: answer as if accepted, keep nothing.
        if (request.website() != null && !request.website().isBlank()) {
            return new SetupRequestResponse(UUID.randomUUID(), false, false, weekStart(today()));
        }

        // The same number asking again (a double tap, a second visit) is the same request: it
        // must not take a second founding place. Checked before the rate limit, so a returning
        // shop always gets its answer.
        List<Existing> existing = jdbc.query(
                "SELECT id, counts_as_founding, created_at FROM setup_requests"
                        + " WHERE whatsapp = :whatsapp AND status <> 'NOT_A_FIT' AND created_at > :since"
                        + " ORDER BY created_at DESC LIMIT 1",
                new MapSqlParameterSource()
                        .addValue("whatsapp", whatsapp)
                        .addValue("since", OffsetDateTime.now(clock).minus(properties.duplicateWindow())),
                (rs, rowNum) -> new Existing(
                        rs.getObject("id", UUID.class),
                        rs.getBoolean("counts_as_founding"),
                        rs.getObject("created_at", OffsetDateTime.class)));
        if (!existing.isEmpty()) {
            Existing row = existing.getFirst();
            return new SetupRequestResponse(
                    row.id(), row.founding(), true, weekStart(row.createdAt().atZoneSameInstant(properties.zone()).toLocalDate()));
        }

        for (String key : List.of("address:" + callerAddress, "whatsapp:" + whatsapp)) {
            Duration wait = rateLimiter.tryAcquire(key);
            if (wait != null) {
                throw new SetupRequestThrottledException(wait);
            }
        }

        FoundingOfferStatus offer = status();
        // Booked into the first week with room: this week, unless its setups are already taken.
        LocalDate setupWeek = offer.open()
                ? weekStart(today()).plusWeeks(offer.bookedThisWeek() / properties.weeklyCapacity())
                : weekStart(today());

        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO setup_requests (id, business_name, whatsapp, source, counts_as_founding, created_at)"
                        + " VALUES (:id, :businessName, :whatsapp, :source, :founding, :createdAt)",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("businessName", request.businessName().trim())
                        .addValue("whatsapp", whatsapp)
                        .addValue("source", request.source() == null ? "landing" : request.source())
                        .addValue("founding", offer.open())
                        .addValue("createdAt", OffsetDateTime.now(clock)));
        log.info("Setup requested: {} ({}), founding={}, week of {}", request.businessName().trim(), whatsapp, offer.open(), setupWeek);
        return new SetupRequestResponse(id, offer.open(), false, setupWeek);
    }

    private int foundingTaken() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM setup_requests WHERE counts_as_founding AND status <> 'NOT_A_FIT'",
                Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    private int foundingSince(LocalDate monday) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM setup_requests WHERE counts_as_founding AND status <> 'NOT_A_FIT' AND created_at >= :since",
                new MapSqlParameterSource("since", monday.atStartOfDay(properties.zone()).toOffsetDateTime()),
                Integer.class);
        return count == null ? 0 : count;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(properties.zone()));
    }

    private record Existing(UUID id, boolean founding, OffsetDateTime createdAt) {
    }

    private static LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
