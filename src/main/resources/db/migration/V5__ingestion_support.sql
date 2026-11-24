-- customer-এর external_id দিয়ে খুঁজব, তাই এটা unique হওয়া দরকার
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_external_id ON customer (external_id);

-- পরে filter (region, endpoint) আর late event-এর জন্য লাগবে
ALTER TABLE usage_event ADD COLUMN IF NOT EXISTS properties JSONB;
ALTER TABLE usage_event ADD COLUMN IF NOT EXISTS late BOOLEAN NOT NULL DEFAULT FALSE;