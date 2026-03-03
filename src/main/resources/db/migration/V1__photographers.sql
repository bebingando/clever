-- Photographers: parent entity. One photographer owns many photos.
CREATE TABLE photographers (
    photographer_id   BIGINT  PRIMARY KEY,
    name              TEXT    NOT NULL,
    profile_url       TEXT    NOT NULL
);
