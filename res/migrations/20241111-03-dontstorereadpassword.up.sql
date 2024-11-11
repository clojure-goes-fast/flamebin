ALTER TABLE profile
DROP COLUMN read_password;

--;;

ALTER TABLE profile
ADD COLUMN edit_token TEXT;

--;;

ALTER TABLE profile
ADD COLUMN is_public INTEGER;
