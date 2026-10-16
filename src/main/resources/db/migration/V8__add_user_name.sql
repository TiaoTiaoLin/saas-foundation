-- A person's display name follows the account across organizations.
-- Existing rows keep an empty name until the person sets one.

ALTER TABLE users
    ADD COLUMN name VARCHAR(80) NOT NULL DEFAULT '';
