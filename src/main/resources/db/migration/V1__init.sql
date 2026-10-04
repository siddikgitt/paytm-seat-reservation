CREATE TABLE shows (
    id             uuid        PRIMARY KEY,
    name           text        NOT NULL,
    price_paise    bigint      NOT NULL CHECK (price_paise > 0),
    per_user_limit int         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats    int         NOT NULL CHECK (total_seats > 0),
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- One row per physical seat. The row is the unit of contention: every state
-- change is a guarded UPDATE on (show_id, label) under a row lock.
CREATE TABLE seats (
    show_id        uuid        NOT NULL REFERENCES shows (id),
    label          text        NOT NULL,
    position       int         NOT NULL,
    status         text        NOT NULL DEFAULT 'available'
                               CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id uuid,
    user_id        text,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (show_id, label),
    -- A seat is owned iff it is not available; makes "taken but ownerless" unrepresentable.
    CHECK ((status = 'available') = (reservation_id IS NULL)),
    CHECK ((status = 'available') = (user_id IS NULL))
);

CREATE INDEX seats_reservation_idx ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

-- The idempotency store is the reservation itself: the unique key makes a
-- second reservation for the same (user, show, key) impossible.
CREATE TABLE reservations (
    id              uuid        PRIMARY KEY,
    show_id         uuid        NOT NULL REFERENCES shows (id),
    user_id         text        NOT NULL,
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    seats           text[]      NOT NULL,
    amount_paise    bigint      NOT NULL CHECK (amount_paise >= 0),
    status          text        NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    cancelled_at    timestamptz,
    CONSTRAINT reservations_idempotency_uq UNIQUE (user_id, show_id, idempotency_key)
);

CREATE INDEX reservations_user_show_idx ON reservations (show_id, user_id);

-- Per-user seat counter; the CHECK plus a conditional upsert enforces the limit.
CREATE TABLE user_show_quota (
    show_id    uuid NOT NULL REFERENCES shows (id),
    user_id    text NOT NULL,
    seats_held int  NOT NULL CHECK (seats_held >= 0),
    PRIMARY KEY (show_id, user_id)
);
