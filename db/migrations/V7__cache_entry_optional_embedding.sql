-- Exact-only caching (routing.yaml `cache.semantic: false`) stores
-- entries without ever running the embedding model, so those rows have
-- no embedding. A NULL embedding also keeps the row out of every
-- semantic (ANN) search: the HNSW index does not index NULLs, and
-- PgVectorCacheStore.findNearest filters them out explicitly. Exact
-- lookups by (namespace, exact_hash) are unaffected.
ALTER TABLE cache_entry ALTER COLUMN embedding DROP NOT NULL;
