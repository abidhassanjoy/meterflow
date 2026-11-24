CREATE TABLE event_publication (
                                   id               UUID NOT NULL PRIMARY KEY,
                                   listener_id      TEXT NOT NULL,
                                   event_type       TEXT NOT NULL,
                                   serialized_event TEXT NOT NULL,
                                   publication_date TIMESTAMPTZ NOT NULL,
                                   completion_date  TIMESTAMPTZ
);

CREATE INDEX idx_event_publication_serialized_event_hash
    ON event_publication USING hash (serialized_event);

CREATE INDEX idx_event_publication_by_completion_date
    ON event_publication (completion_date);