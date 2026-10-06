package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.RefreshToken;
import com.procurepal_services.stock_bridge_api.entity.SubjectType;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Row-locks the token for the caller's transaction, so two refreshes presenting the same token
     * at once (two tabs restoring one session) serialize: the second sees the first's rotation
     * instead of both reading the token as live and racing on {@code replaced_by_id}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.id = :id")
    Optional<RefreshToken> findByIdForUpdate(@Param("id") UUID id);

    /** Every live session for one subject - a password reset logs out every device. */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.subjectType = :subjectType AND t.subjectId = :subjectId AND t.revokedAt IS NULL")
    int revokeAllForSubject(
            @Param("subjectType") SubjectType subjectType,
            @Param("subjectId") UUID subjectId,
            @Param("now") OffsetDateTime now);
}
