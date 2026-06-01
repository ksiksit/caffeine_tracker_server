CREATE TABLE half_life_observations (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    obs_date             DATE         NOT NULL,
    predicted_residual   DOUBLE       NOT NULL,
    observed_sol_minutes DOUBLE       NOT NULL,
    prior_mean           DOUBLE       NOT NULL,
    prior_variance       DOUBLE       NOT NULL,
    posterior_mean       DOUBLE       NOT NULL,
    posterior_variance   DOUBLE       NOT NULL,
    condition_multiplier DOUBLE       NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_half_life_obs_user_date (user_id, obs_date),
    CONSTRAINT fk_half_life_obs_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
