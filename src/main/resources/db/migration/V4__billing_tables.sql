-- Billable metric: কী গুনব
CREATE TABLE billable_metric (
                                 id          BIGSERIAL PRIMARY KEY,
                                 code        VARCHAR(100) NOT NULL UNIQUE,
                                 name        VARCHAR(200) NOT NULL,
                                 aggregation VARCHAR(30)  NOT NULL,   -- SUM, COUNT, MAX, UNIQUE_COUNT, LATEST
                                 property    VARCHAR(100)             -- event-এর কোন field গুনব
);

-- Plan: code + version মিলে একটা plan
CREATE TABLE plan (
                      id        BIGSERIAL PRIMARY KEY,
                      code      VARCHAR(100) NOT NULL,
                      name      VARCHAR(200) NOT NULL,
                      version   INT          NOT NULL DEFAULT 1,
                      currency  CHAR(3)      NOT NULL,     -- BDT বা USD
                      CONSTRAINT uq_plan_code_version UNIQUE (code, version)
);

-- Plan-এর charge: config JSON-এ থাকে, তাই নতুন plan = শুধু data
CREATE TABLE plan_charge (
                             id          BIGSERIAL PRIMARY KEY,
                             plan_id     BIGINT      NOT NULL REFERENCES plan(id),
                             metric_code VARCHAR(100),
                             charge_type VARCHAR(30) NOT NULL,    -- FLAT, PER_UNIT, GRADUATED, VOLUME, PACKAGE, PER_SEAT
                             config      JSONB       NOT NULL,
                             sort_order  INT         NOT NULL DEFAULT 0
);

-- Subscription
CREATE TABLE subscription (
                              id                   BIGSERIAL PRIMARY KEY,
                              customer_id          BIGINT      NOT NULL REFERENCES customer(id),
                              plan_id              BIGINT      NOT NULL REFERENCES plan(id),
                              status               VARCHAR(20) NOT NULL,   -- TRIAL, ACTIVE, PAUSED, CANCELLED
                              current_period_start DATE        NOT NULL,
                              current_period_end   DATE        NOT NULL,
                              version              BIGINT      NOT NULL DEFAULT 0   -- optimistic locking
);
CREATE INDEX idx_subscription_customer ON subscription (customer_id);

-- Invoice: (customer, period) unique, তাই একই period-এ দুটো invoice হবেই না
CREATE TABLE invoice (
                         id              BIGSERIAL PRIMARY KEY,
                         customer_id     BIGINT         NOT NULL REFERENCES customer(id),
                         subscription_id BIGINT         NOT NULL REFERENCES subscription(id),
                         period_start    DATE           NOT NULL,
                         period_end      DATE           NOT NULL,
                         status          VARCHAR(20)    NOT NULL,     -- DRAFT, FINALISED, PAID, VOID
                         currency        CHAR(3)        NOT NULL,
                         subtotal        NUMERIC(19,2)  NOT NULL DEFAULT 0,
                         tax             NUMERIC(19,2)  NOT NULL DEFAULT 0,
                         total           NUMERIC(19,2)  NOT NULL DEFAULT 0,
                         created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
                         CONSTRAINT uq_invoice_period UNIQUE (customer_id, period_start, period_end)
);

-- Invoice line: কোন rule-এ কত event থেকে কত টাকা (QA5 transparency)
CREATE TABLE invoice_line (
                              id          BIGSERIAL PRIMARY KEY,
                              invoice_id  BIGINT         NOT NULL REFERENCES invoice(id),
                              description VARCHAR(300)   NOT NULL,
                              quantity    NUMERIC(19,6)  NOT NULL DEFAULT 0,
                              event_count BIGINT         NOT NULL DEFAULT 0,
                              price_rule  VARCHAR(500)   NOT NULL,
                              amount      NUMERIC(19,2)  NOT NULL
);
CREATE INDEX idx_invoice_line_invoice ON invoice_line (invoice_id);

-- কোন event কোন invoice-এ billed হয়েছে, সেটা রাখার জন্য (exactly-once billing)
ALTER TABLE usage_event ADD COLUMN IF NOT EXISTS billed_invoice_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_usage_event_billed ON usage_event (billed_invoice_id);