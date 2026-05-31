CREATE TABLE caffeine_records (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    amount      INT          NOT NULL,
    drink_name  VARCHAR(100) NOT NULL,
    timestamp   DATETIME(6)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_caffeine_records_user_id_timestamp (user_id, timestamp),
    CONSTRAINT fk_caffeine_records_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
