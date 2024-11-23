CREATE TABLE stats (
    stat TEXT PRIMARY KEY,
    val INTEGER NOT NULL
);

--;;

INSERT INTO stats (stat, val) VALUES ('total_uploaded', 0);
