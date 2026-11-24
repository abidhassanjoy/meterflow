ALTER TABLE invoice_line ADD COLUMN IF NOT EXISTS metric_code VARCHAR(100);
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS finalised_at TIMESTAMPTZ;

-- এখনও বিল না হওয়া event দ্রুত খুঁজে পেতে
CREATE INDEX IF NOT EXISTS idx_usage_event_unbilled
    ON usage_event (customer_id, occurred_at) WHERE billed_invoice_id IS NULL;