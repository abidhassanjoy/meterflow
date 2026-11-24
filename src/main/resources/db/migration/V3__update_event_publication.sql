ALTER TABLE event_publication
    ADD COLUMN completion_attempts    INT NOT NULL DEFAULT 0,
    ADD COLUMN last_resubmission_date TIMESTAMPTZ,
    ADD COLUMN status                 VARCHAR(20);