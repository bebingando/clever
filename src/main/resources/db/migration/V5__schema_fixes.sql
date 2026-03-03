-- V5: Schema correctness and index improvements
--
-- 1. avg_color: CHAR(7) pads silently on short inputs; switch to TEXT with an
--    explicit CHECK constraint so the format is enforced at the DB level.
ALTER TABLE photos ALTER COLUMN avg_color TYPE TEXT;
ALTER TABLE photos ADD CONSTRAINT chk_photos_avg_color
    CHECK (avg_color ~* '^#[0-9A-Fa-f]{6}$');

-- 2. Photographer name search: a B-tree index is useless for ILIKE '%term%'.
--    Replace it with a trigram GIN index so partial-name searches use the index.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
DROP INDEX IF EXISTS idx_photographers_name;
CREATE INDEX idx_photographers_name_trgm
    ON photographers USING GIN (name gin_trgm_ops);

-- 3. Dimension filters: a composite (width, height) index is rarely used for
--    range+range queries. Split into two single-column indexes; the planner can
--    merge them via a bitmap-AND scan for AND-combined predicates.
--
--    NOTE: These statements run inside a Flyway transaction. For future live-table
--    index additions on large tables, use a separate non-transactional migration:
--      -- flyway:executeInTransaction=false
--    and add CONCURRENTLY to avoid locking.
DROP INDEX IF EXISTS idx_photos_dimensions;
CREATE INDEX idx_photos_width  ON photos (width);
CREATE INDEX idx_photos_height ON photos (height);
