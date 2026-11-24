CREATE TABLE customer (
                          id          BIGSERIAL PRIMARY KEY,
                          external_id VARCHAR(100) NOT NULL UNIQUE,
                          name        VARCHAR(200) NOT NULL,
                          currency    VARCHAR(3)   NOT NULL,
                          created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE usage_event (
                             id              BIGSERIAL PRIMARY KEY,
                             customer_id     BIGINT        NOT NULL REFERENCES customer(id),
                             metric          VARCHAR(100)  NOT NULL,
                             quantity        NUMERIC(20,6) NOT NULL,
                             occurred_at     TIMESTAMPTZ   NOT NULL,
                             idempotency_key VARCHAR(200)  NOT NULL,
                             received_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
                             CONSTRAINT uq_usage_event_idem UNIQUE (customer_id, idempotency_key)
);

CREATE INDEX idx_usage_event_lookup
    ON usage_event (customer_id, metric, occurred_at);