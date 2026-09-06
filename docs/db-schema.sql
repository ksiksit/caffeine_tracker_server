-- =====================================================================
-- 카페인 트래커 서버 DB 스키마 (MySQL 8) — 사용자가 직접 실행하는 DDL 원본
--
-- 적용: mysql -u caffeine -p caffeine_tracker < docs/db-schema.sql
-- 설명: docs/db-schema.md (공통 규칙 · 관계 · 변경 절차 · 변경 이력)
--
-- * 애플리케이션은 이 파일을 실행하지 않는다. 기동 시 엔티티와 대조만 한다(ddl-auto: validate).
-- * 테스트는 이 파일을 H2(MODE=MySQL)에 그대로 적용해 엔티티와 대조한다
--   (build.gradle 의 processTestResources 가 복사). H2 에서 안 도는 문법은 쓰지 말 것.
-- * 모든 문장은 IF NOT EXISTS 라 여러 번 실행해도 안전하다.
--   이미 있는 테이블의 변경은 db-schema.md 변경 이력의 ALTER 로 별도 적용한다.
-- =====================================================================

-- users: 계정
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,                 -- 로그인 ID
    password_hash VARCHAR(255) NOT NULL,                 -- BCrypt 해시 (평문 미저장)
    nickname      VARCHAR(50)  NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',  -- 'USER' | 'ADMIN'
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- refresh_tokens: 리프레시 토큰 (SHA-256 해시만 저장)
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,  -- 토큰 원문의 SHA-256 소문자 hex (평문 미저장)
    expires_at  DATETIME(6)  NOT NULL,  -- 만료 시각 (기본 발급 + 14일)
    revoked_at  DATETIME(6)  NULL,      -- 폐기 시각. NULL 이면 유효 (로그아웃·회전 시 기록)
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_token_hash (token_hash),
    KEY idx_refresh_tokens_user_id_revoked (user_id, revoked_at),  -- 사용자 토큰 일괄 폐기 조회용
    CONSTRAINT fk_refresh_tokens_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- user_settings: 유저 설정 + 베이지안 학습 상태 (users 와 1:1, PK 공유)
CREATE TABLE IF NOT EXISTS user_settings (
    user_id                 BIGINT       NOT NULL,  -- users.id 와 동일 (공유 PK, 1:1)
    half_life               DOUBLE       NOT NULL,  -- 수동 설정 반감기(시간). 기본 5.0, 연산 시 3.0~7.0 clamp
    health_condition        INT          NOT NULL,  -- 건강 상태: 0=NONE, 1=SMOKER, 2=ORAL_CONTRACEPTIVE, 3=PREGNANT
    bedtime_hour            INT          NOT NULL,  -- 취침 시각(로컬). 기본 23
    bedtime_minute          INT          NOT NULL,  -- 기본 0
    reference_dose_mg       INT          NOT NULL,  -- 기준 용량(mg). 기본 75
    is_learning_enabled     BIT(1)       NOT NULL,  -- 자동 학습 여부. 기본 1
    notify_cutoff           BIT(1)       NOT NULL,  -- 섭취 마감 알림 on/off. 기본 1
    notify_bedtime_residual BIT(1)       NOT NULL,  -- 취침 잔량 예고 on/off. 기본 1
    notify_record_reminder  BIT(1)       NOT NULL,  -- 기록 리마인더 on/off. 기본 1
    learned_mean            DOUBLE       NOT NULL,  -- 학습 반감기 평균(시간). 기본 5.0, 반감기 수동 변경 시 그 값으로 리셋
    learned_variance        DOUBLE       NOT NULL,  -- 학습 반감기 분산(시간²). 기본 2.25(모집단 1.5²), 반감기 수동 변경 시 리셋
    last_learned_date       DATE         NULL,      -- 마지막 학습 날짜(로컬). 반감기 수동 변경 시 NULL 로 리셋
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_settings_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- caffeine_records: 카페인 섭취 기록
CREATE TABLE IF NOT EXISTS caffeine_records (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    amount      INT          NOT NULL,  -- 카페인량(mg)
    drink_name  VARCHAR(100) NOT NULL,  -- 음료명
    timestamp   DATETIME(6)  NOT NULL,  -- 섭취 시각(UTC)
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_caffeine_records_user_id_timestamp (user_id, timestamp),  -- 기간 조회용
    CONSTRAINT fk_caffeine_records_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- sleep_samples: HealthKit 원시 수면 샘플
CREATE TABLE IF NOT EXISTS sleep_samples (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    client_uuid VARCHAR(64)  NOT NULL,  -- 기기(HealthKit) 샘플 UUID. 멱등 업로드 키
    start_at    DATETIME(6)  NOT NULL,  -- 샘플 시작(UTC)
    end_at      DATETIME(6)  NOT NULL,  -- 샘플 종료(UTC)
    hk_value    INT          NOT NULL,  -- HKCategoryValueSleepAnalysis rawValue: 0=inBed, 1=unspecified, 2=awake, 3=core, 4=deep, 5=rem
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sleep_samples_user_client (user_id, client_uuid),  -- 같은 샘플 재업로드 시 중복 방지
    KEY idx_sleep_samples_user_start (user_id, start_at),  -- 기간 조회용
    CONSTRAINT fk_sleep_samples_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- half_life_observations: 반감기 학습 관측 이력 (유저·날짜당 1건)
CREATE TABLE IF NOT EXISTS half_life_observations (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    obs_date             DATE         NOT NULL,  -- 관측 날짜 = 그 밤이 끝나는 아침의 로컬 날짜
    predicted_residual   DOUBLE       NOT NULL,  -- 취침 시 예측 카페인 잔량(mg)
    observed_sol_minutes DOUBLE       NOT NULL,  -- 관측 수면 잠복기 SOL(분)
    prior_mean           DOUBLE       NOT NULL,  -- 학습 전 prior 평균(시간)
    prior_variance       DOUBLE       NOT NULL,  -- 학습 전 prior 분산(시간²)
    posterior_mean       DOUBLE       NOT NULL,  -- 학습 후 posterior 평균(시간)
    posterior_variance   DOUBLE       NOT NULL,  -- 학습 후 posterior 분산(시간²)
    condition_multiplier DOUBLE       NOT NULL,  -- 학습 당시 건강 상태 보정 계수 (1.0 / 0.5 / 2.0 / 2.5)
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_half_life_obs_user_date (user_id, obs_date),  -- 같은 날 1회만 학습
    CONSTRAINT fk_half_life_obs_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
