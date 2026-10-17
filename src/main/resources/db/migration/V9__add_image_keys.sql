-- Object paths in the private image bucket. Empty means no image.
-- The signed URL is created when a response is built and is not stored.

ALTER TABLE users ADD COLUMN avatar_key VARCHAR(160);

ALTER TABLE tenants ADD COLUMN logo_key VARCHAR(160);
