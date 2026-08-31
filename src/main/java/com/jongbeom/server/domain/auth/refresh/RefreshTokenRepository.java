package com.jongbeom.server.domain.auth.refresh;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken r set r.revokedAt = :now "
            + "where r.userId = :userId and r.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
