ALTER TABLE users ADD COLUMN user_code VARCHAR(20);

WITH numbered_users AS (
    SELECT id, 'AD' || LPAD(ROW_NUMBER() OVER (ORDER BY id)::text, 6, '0') AS generated_code
    FROM users
)
UPDATE users
SET user_code = numbered_users.generated_code
FROM numbered_users
WHERE users.id = numbered_users.id;

ALTER TABLE users ALTER COLUMN user_code SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_tenant_user_code UNIQUE (tenant_id, user_code);
