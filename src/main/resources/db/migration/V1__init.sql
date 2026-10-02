-- MySQL 8.0.16+ (CHECK constraints enforced), InnoDB. utf8mb4_bin: seat labels are case-sensitive
-- and sort in a deterministic binary order, which the lock-ordering argument relies on.

-- A show is immutable once created: name, price and per-user limit never change.
CREATE TABLE shows (
    id             VARCHAR(36)  NOT NULL,
    name           VARCHAR(200) NOT NULL,
    price_paise    BIGINT       NOT NULL,
    per_user_limit INT          NOT NULL,
    total_seats    INT          NOT NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT shows_price_ck CHECK (price_paise >= 0),
    CONSTRAINT shows_limit_ck CHECK (per_user_limit > 0),
    CONSTRAINT shows_total_ck CHECK (total_seats > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- One row per physical seat. PRIMARY KEY (show_id, label) means a seat exists exactly once and a
-- single reservation_id column means it can have at most one owner: a double-sell is not
-- representable. Every state change is a guarded UPDATE under a row lock.
CREATE TABLE seats (
    show_id        VARCHAR(36) NOT NULL,
    label          VARCHAR(16) NOT NULL,
    position       INT         NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'available',
    reservation_id VARCHAR(36) NULL,
    user_id        VARCHAR(64) NULL,
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (show_id, label),
    KEY seats_position_idx (show_id, position),
    KEY seats_reservation_idx (reservation_id),
    CONSTRAINT seats_show_fk FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT seats_status_ck CHECK (status IN ('available', 'held', 'confirmed')),
    -- an available seat has no owner; a held/confirmed seat always has one
    CONSTRAINT seats_owner_ck CHECK ((status = 'available') = (reservation_id IS NULL)),
    CONSTRAINT seats_user_ck CHECK ((reservation_id IS NULL) = (user_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- Successful reservations only. UNIQUE (user_id, idempotency_key) is the exactly-once guard: a
-- concurrent duplicate INSERT waits on the index entry until the first transaction finishes, then
-- fails with a duplicate-key error (first committed -> replay) or proceeds (first rolled back).
CREATE TABLE reservations (
    id              VARCHAR(36)   NOT NULL,
    show_id         VARCHAR(36)   NOT NULL,
    user_id         VARCHAR(64)   NOT NULL,
    seats           VARCHAR(4096) NOT NULL, -- comma-separated labels; labels can't contain ','
    amount_paise    BIGINT        NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    idempotency_key VARCHAR(128)  NOT NULL,
    request_hash    CHAR(64)      NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    cancelled_at    DATETIME(6)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY reservations_user_idem_uq (user_id, idempotency_key),
    KEY reservations_show_idx (show_id),
    CONSTRAINT reservations_show_fk FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT reservations_status_ck CHECK (status IN ('confirmed', 'cancelled')),
    CONSTRAINT reservations_amount_ck CHECK (amount_paise >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- Per-user seat counter per show. The limit is enforced by a conditional UPDATE on this row, which
-- serialises parallel requests from the same user without touching other users.
CREATE TABLE user_show_holds (
    show_id    VARCHAR(36) NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    seats_held INT         NOT NULL,
    PRIMARY KEY (show_id, user_id),
    CONSTRAINT holds_show_fk FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT holds_nonneg_ck CHECK (seats_held >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
