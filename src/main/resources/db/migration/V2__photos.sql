-- Photos: child entity. src.* URL variants are NOT stored; they are
-- reconstructed at the API layer by appending query-string params to
-- base_image_url, saving ~400 bytes/row at scale.
CREATE TABLE photos (
    id              BIGINT   PRIMARY KEY,
    photographer_id BIGINT   NOT NULL
                     REFERENCES photographers(photographer_id)
                     ON DELETE RESTRICT,
    width           INT      NOT NULL,
    height          INT      NOT NULL,
    pexels_url      TEXT     NOT NULL,
    base_image_url  TEXT     NOT NULL,  -- e.g. https://images.pexels.com/photos/{id}/pexels-photo-{id}.jpeg
    avg_color       CHAR(7)  NOT NULL,  -- hex colour, e.g. '#333831'
    alt             TEXT                -- nullable: not all photos have alt text
);
