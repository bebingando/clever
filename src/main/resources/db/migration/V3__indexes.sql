-- FK support: prevents sequential scan on photos when filtering by photographer.
-- Supports GET /photos?photographer_id=... and GET /photographers/:id/photos.
CREATE INDEX idx_photos_photographer_id
    ON photos (photographer_id);

-- Photographer lookup by name (used by name-search endpoint, if added later).
CREATE INDEX idx_photographers_name
    ON photographers (name);

-- GIN index for full-text search on alt text.
-- Powers GET /photos?alt=keyword using to_tsvector/to_tsquery.
-- coalesce handles NULL alt values gracefully.
CREATE INDEX idx_photos_alt_fts
    ON photos
    USING GIN (to_tsvector('english', coalesce(alt, '')));

-- Composite B-tree index on (width, height) for dimension-range queries.
-- Powers GET /photos?min_width=...&min_height=...
CREATE INDEX idx_photos_dimensions
    ON photos (width, height);
