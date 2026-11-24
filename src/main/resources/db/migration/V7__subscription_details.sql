ALTER TABLE subscription ADD COLUMN IF NOT EXISTS trial_ends_at DATE;
ALTER TABLE subscription ADD COLUMN IF NOT EXISTS convert_to_paid BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE subscription ADD COLUMN IF NOT EXISTS pending_plan_id BIGINT REFERENCES plan(id);
ALTER TABLE subscription ADD COLUMN IF NOT EXISTS billing_anchor_day INT NOT NULL DEFAULT 1;
ALTER TABLE subscription ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- একজন customer-এর একটাই চালু (বাতিল নয়) subscription
CREATE UNIQUE INDEX IF NOT EXISTS uq_subscription_one_live_per_customer
    ON subscription (customer_id) WHERE status <> 'CANCELLED';

-- proration-এর মতো সমন্বয়। billing run-এ invoice-এ উঠবে (দিন ৬-৭)
CREATE TABLE IF NOT EXISTS subscription_adjustment (
                                                       id                BIGSERIAL PRIMARY KEY,
                                                       subscription_id   BIGINT        NOT NULL REFERENCES subscription(id) ON DELETE CASCADE,
    amount            NUMERIC(19,2) NOT NULL,
    description       VARCHAR(300)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    billed_invoice_id BIGINT
    );