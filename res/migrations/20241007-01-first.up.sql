CREATE TABLE user (
    github_name TEXT PRIMARY KEY,
    email TEXT
);

--;;

CREATE TABLE profile (
    id TEXT PRIMARY KEY,
    file_path TEXT NOT NULL,
    owner TEXT,
    upload_ts TIMESTAMP NOT NULL,
    sample_count INTEGER,
    profile_type TEXT NOT NULL
    -- FOREIGN KEY (user_id) REFERENCES user(id),
    -- FOREIGN KEY (profile_type) REFERENCES profile_type(name)
);

-- CREATE INDEX idx_profiles_upload_timestamp ON Profiles(upload_timestamp);
-- CREATE INDEX idx_profiles_user_id ON Profiles(user_id);
