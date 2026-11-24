-- কোন plan version দিয়ে invoice হয়েছিল, পরে late event-এর দাম হিসাবে লাগবে
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS plan_code    VARCHAR(100);
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS plan_version INT;

-- আগের invoice গুলোর জন্য যতটা সম্ভব ভরে দিই
UPDATE invoice i
SET plan_code = p.code, plan_version = p.version
    FROM subscription s JOIN plan p ON p.id = s.plan_id
WHERE s.id = i.subscription_id AND i.plan_code IS NULL;

-- credit note: বিবাদের জন্য সংশোধন
CREATE TABLE IF NOT EXISTS credit_note (
                                           id          BIGSERIAL PRIMARY KEY,
                                           invoice_id  BIGINT        NOT NULL REFERENCES invoice(id),
    customer_id BIGINT        NOT NULL REFERENCES customer(id),
    amount      NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    reason      VARCHAR(200)  NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
    );
CREATE INDEX IF NOT EXISTS idx_credit_note_invoice ON credit_note (invoice_id);