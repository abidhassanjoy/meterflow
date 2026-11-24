-- কর নিয়ম, যেমন VAT ১৫%
CREATE TABLE IF NOT EXISTS tax_rule (
                                        code VARCHAR(50)  PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    rate NUMERIC(7,4) NOT NULL CHECK (rate >= 0 AND rate <= 1)
    );

ALTER TABLE customer ADD COLUMN IF NOT EXISTS tax_rule_code VARCHAR(50) REFERENCES tax_rule(code);

-- invoice line: ধরন আর কর
ALTER TABLE invoice_line ADD COLUMN IF NOT EXISTS line_type  VARCHAR(20)   NOT NULL DEFAULT 'USAGE';
ALTER TABLE invoice_line ADD COLUMN IF NOT EXISTS tax_rule   VARCHAR(100);
ALTER TABLE invoice_line ADD COLUMN IF NOT EXISTS tax_rate   NUMERIC(7,4)  NOT NULL DEFAULT 0;
ALTER TABLE invoice_line ADD COLUMN IF NOT EXISTS tax_amount NUMERIC(19,2) NOT NULL DEFAULT 0;

-- invoice: কত credit কাটা হলো আর কত বাকি
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS credit_applied NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS amount_due     NUMERIC(19,2) NOT NULL DEFAULT 0;
UPDATE invoice SET amount_due = total;

-- prepaid credit খাতা: ধনাত্মক = জমা, ঋণাত্মক = ব্যবহার
CREATE TABLE IF NOT EXISTS credit_ledger (
                                             id          BIGSERIAL PRIMARY KEY,
                                             customer_id BIGINT        NOT NULL REFERENCES customer(id),
    amount      NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    reason      VARCHAR(300)  NOT NULL,
    invoice_id  BIGINT REFERENCES invoice(id),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
    );
CREATE INDEX IF NOT EXISTS idx_credit_ledger_customer ON credit_ledger (customer_id);