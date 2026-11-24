-- metric কোন event-এর নাম গুনবে (ফাঁকা থাকলে metric-এর code-ই ধরা হবে)
ALTER TABLE billable_metric ADD COLUMN IF NOT EXISTS event_name VARCHAR(100);

-- metric-এর filter: একটা metric-এর একাধিক সারি থাকলে সবগুলো একসাথে খাটে (AND)
CREATE TABLE billable_metric_filter (
                                        id             BIGSERIAL PRIMARY KEY,
                                        metric_id      BIGINT       NOT NULL REFERENCES billable_metric(id) ON DELETE CASCADE,
                                        property_key   VARCHAR(100) NOT NULL,
                                        property_value VARCHAR(200) NOT NULL
);