-- Galeria online do Lumo: esquema inicial.
CREATE TABLE galleries (
  id TEXT PRIMARY KEY,
  slug TEXT NOT NULL UNIQUE,
  client_key TEXT UNIQUE,
  title TEXT NOT NULL,
  pin_hash TEXT,
  allow_download INTEGER NOT NULL DEFAULT 1,
  status TEXT NOT NULL DEFAULT 'active',
  sort_order TEXT NOT NULL DEFAULT 'desc',
  expires_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE photos (
  id TEXT PRIMARY KEY,
  gallery_id TEXT NOT NULL REFERENCES galleries(id) ON DELETE CASCADE,
  client_photo_id TEXT NOT NULL,
  filename TEXT NOT NULL,
  taken_at INTEGER NOT NULL,
  width INTEGER NOT NULL DEFAULT 0,
  height INTEGER NOT NULL DEFAULT 0,
  revision INTEGER NOT NULL,
  ready_revision INTEGER,
  full_size INTEGER NOT NULL DEFAULT 0,
  first_ready_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE (gallery_id, client_photo_id)
);
CREATE INDEX idx_photos_list ON photos (gallery_id, taken_at, id);
CREATE INDEX idx_photos_ready ON photos (gallery_id, first_ready_at);

CREATE TABLE photo_variants (
  photo_id TEXT NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
  revision INTEGER NOT NULL,
  variant TEXT NOT NULL,
  size INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  uploaded INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (photo_id, revision, variant)
);

CREATE TABLE visitors (
  gallery_id TEXT NOT NULL REFERENCES galleries(id) ON DELETE CASCADE,
  id TEXT NOT NULL,
  name TEXT,
  created_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  PRIMARY KEY (gallery_id, id)
);

CREATE TABLE favorites (
  gallery_id TEXT NOT NULL REFERENCES galleries(id) ON DELETE CASCADE,
  photo_id TEXT NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
  visitor_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (photo_id, visitor_id)
);
CREATE INDEX idx_favorites_visitor ON favorites (gallery_id, visitor_id);

CREATE TABLE pin_failures (
  gallery_id TEXT NOT NULL,
  ip TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL,
  PRIMARY KEY (gallery_id, ip)
);
