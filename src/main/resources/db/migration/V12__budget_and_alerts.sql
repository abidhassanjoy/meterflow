CREATE TABLE IF NOT EXISTS budget (
                                      customer_id BIGINT PRIMARY KEY REFERENCES customer(id),
    amount      NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
    );

CREATE TABLE IF NOT EXISTS budget_alert (
                                            id          BIGSERIAL PRIMARY KEY,
                                            customer_id BIGINT        NOT NULL REFERENCES customer(id),
    period_start DATE         NOT NULL,
    threshold   INT           NOT NULL CHECK (threshold IN (50, 80, 100)),
    spend       NUMERIC(19,2) NOT NULL,
    budget      NUMERIC(19,2) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (customer_id, period_start, threshold)
    );