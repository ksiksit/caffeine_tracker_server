CREATE TABLE user_settings (
    user_id             BIGINT       NOT NULL,
    half_life           DOUBLE       NOT NULL,
    health_condition    INT          NOT NULL,
    bedtime_hour        INT          NOT NULL,
    bedtime_minute      INT          NOT NULL,
    reference_dose_mg   INT          NOT NULL,
    is_learning_enabled BIT(1)       NOT NULL,
    learned_mean        DOUBLE       NOT NULL,
    learned_variance    DOUBLE       NOT NULL,
    last_learned_date   DATE         NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_settings_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
