package main

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"time"

	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
)

// Store wraps the SQLite database. Item payloads are stored as opaque JSON so
// fields unknown to the server survive round trips; only the columns needed for
// sync (id, updatedAt, rev) are extracted.
type Store struct {
	db  *sql.DB
	dir string
}

var ErrNotFound = errors.New("not found")

const schema = `
CREATE TABLE IF NOT EXISTS users (
	id       INTEGER PRIMARY KEY,
	name     TEXT NOT NULL UNIQUE,
	pwhash   TEXT NOT NULL,
	created  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS tokens (
	hash      TEXT PRIMARY KEY,
	user_id   INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	device    TEXT NOT NULL DEFAULT '',
	created   INTEGER NOT NULL,
	last_used INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS items (
	user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	id         TEXT NOT NULL,
	rev        INTEGER NOT NULL,
	updated_at INTEGER NOT NULL,
	data       TEXT NOT NULL,
	PRIMARY KEY (user_id, id)
);
CREATE INDEX IF NOT EXISTS items_rev ON items(user_id, rev);
CREATE TABLE IF NOT EXISTS counter (
	id  INTEGER PRIMARY KEY CHECK (id = 1),
	rev INTEGER NOT NULL
);
INSERT OR IGNORE INTO counter (id, rev) VALUES (1, 0);
`

func OpenStore(dir string) (*Store, error) {
	path := filepath.Join(dir, "jot.db")
	db, err := sql.Open("sqlite", "file:"+path+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)")
	if err != nil {
		return nil, err
	}
	// A single connection serialises writers; at personal scale this is
	// simpler and faster than fighting SQLITE_BUSY.
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema + attachmentSchema); err != nil {
		db.Close()
		return nil, fmt.Errorf("init schema: %w", err)
	}
	return &Store{db: db, dir: dir}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// ---- users ----

type User struct {
	ID      int64
	Name    string
	Created time.Time
}

func (s *Store) AddUser(name, password string) error {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	_, err = s.db.Exec(`INSERT INTO users (name, pwhash, created) VALUES (?, ?, ?)`, name, string(hash), time.Now().UnixMilli())
	return err
}

func (s *Store) SetPassword(name, password string) error {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	res, err := s.db.Exec(`UPDATE users SET pwhash = ? WHERE name = ?`, string(hash), name)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	// A new password logs out every device.
	_, err = s.db.Exec(`DELETE FROM tokens WHERE user_id = (SELECT id FROM users WHERE name = ?)`, name)
	return err
}

func (s *Store) DeleteUser(name string) error {
	res, err := s.db.Exec(`DELETE FROM users WHERE name = ?`, name)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) Users() ([]User, error) {
	rows, err := s.db.Query(`SELECT id, name, created FROM users ORDER BY name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []User
	for rows.Next() {
		var u User
		var created int64
		if err := rows.Scan(&u.ID, &u.Name, &created); err != nil {
			return nil, err
		}
		u.Created = time.UnixMilli(created)
		out = append(out, u)
	}
	return out, rows.Err()
}

func (s *Store) UserByName(name string) (User, error) {
	var u User
	err := s.db.QueryRow(`SELECT id, name FROM users WHERE name = ?`, name).Scan(&u.ID, &u.Name)
	if errors.Is(err, sql.ErrNoRows) {
		return u, ErrNotFound
	}
	return u, err
}

// dummyHash keeps login timing similar for unknown users.
var dummyHash, _ = bcrypt.GenerateFromPassword([]byte("dummy-password"), bcrypt.DefaultCost)

func (s *Store) CheckPassword(name, password string) (User, error) {
	var u User
	var hash string
	err := s.db.QueryRow(`SELECT id, name, pwhash FROM users WHERE name = ?`, name).Scan(&u.ID, &u.Name, &hash)
	if errors.Is(err, sql.ErrNoRows) {
		bcrypt.CompareHashAndPassword(dummyHash, []byte(password))
		return u, ErrNotFound
	}
	if err != nil {
		return u, err
	}
	if bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) != nil {
		return u, ErrNotFound
	}
	return u, nil
}

// ---- tokens ----

func hashToken(tok string) string {
	h := sha256.Sum256([]byte(tok))
	return hex.EncodeToString(h[:])
}

func (s *Store) NewToken(userID int64, device string) (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	tok := hex.EncodeToString(b)
	now := time.Now().UnixMilli()
	_, err := s.db.Exec(`INSERT INTO tokens (hash, user_id, device, created, last_used) VALUES (?, ?, ?, ?, ?)`,
		hashToken(tok), userID, device, now, now)
	return tok, err
}

func (s *Store) UserForToken(tok string) (User, error) {
	var u User
	h := hashToken(tok)
	var lastUsed int64
	err := s.db.QueryRow(`SELECT u.id, u.name, t.last_used FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.hash = ?`, h).
		Scan(&u.ID, &u.Name, &lastUsed)
	if errors.Is(err, sql.ErrNoRows) {
		return u, ErrNotFound
	}
	if err != nil {
		return u, err
	}
	// Avoid a write on every request; hourly precision is plenty.
	if now := time.Now().UnixMilli(); now-lastUsed > int64(time.Hour/time.Millisecond) {
		s.db.Exec(`UPDATE tokens SET last_used = ? WHERE hash = ?`, now, h)
	}
	return u, nil
}

func (s *Store) DeleteToken(tok string) error {
	_, err := s.db.Exec(`DELETE FROM tokens WHERE hash = ?`, hashToken(tok))
	return err
}

// ---- items ----

// ItemMeta holds the fields the server needs from an item payload.
type ItemMeta struct {
	ID        string `json:"id"`
	UpdatedAt int64  `json:"updatedAt"`
}

// Sync merges changes into the stored items (field by field, see mergeItems) and returns every item
// of the user with rev > since, plus the current global rev.
func (s *Store) Sync(userID, since int64, changes []json.RawMessage) (int64, []json.RawMessage, bool, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return 0, nil, false, err
	}
	defer tx.Rollback()

	var rev int64
	if err := tx.QueryRow(`SELECT rev FROM counter WHERE id = 1`).Scan(&rev); err != nil {
		return 0, nil, false, err
	}
	changed := false
	var losers []string
	for _, raw := range changes {
		var m ItemMeta
		if err := json.Unmarshal(raw, &m); err != nil {
			return 0, nil, false, fmt.Errorf("%w: %v", errBadItem, err)
		}
		if m.ID == "" || len(m.ID) > 128 {
			return 0, nil, false, fmt.Errorf("%w: missing or overlong id", errBadItem)
		}
		data, err := withoutRev(raw)
		if err != nil {
			return 0, nil, false, fmt.Errorf("%w: %v", errBadItem, err)
		}
		updatedAt := m.UpdatedAt
		var stored string
		err = tx.QueryRow(`SELECT data FROM items WHERE user_id = ? AND id = ?`, userID, m.ID).Scan(&stored)
		switch {
		case errors.Is(err, sql.ErrNoRows):
		case err != nil:
			return 0, nil, false, err
		default:
			// Merge field by field (SPEC §2b) so concurrent edits on two devices both survive.
			var st, in map[string]json.RawMessage
			if err := json.Unmarshal([]byte(stored), &st); err != nil {
				return 0, nil, false, fmt.Errorf("stored item %s: %w", m.ID, err)
			}
			json.Unmarshal([]byte(data), &in)
			merged := mergeItems(st, in)
			b, err := json.Marshal(merged)
			if err != nil {
				return 0, nil, false, err
			}
			if string(b) == stored {
				losers = append(losers, m.ID) // nothing new; make sure the client gets the stored copy
				continue
			}
			data = string(b)
			json.Unmarshal(merged["updatedAt"], &updatedAt)
		}
		rev++
		changed = true
		if _, err := tx.Exec(`INSERT INTO items (user_id, id, rev, updated_at, data) VALUES (?, ?, ?, ?, ?)
			ON CONFLICT (user_id, id) DO UPDATE SET rev = excluded.rev, updated_at = excluded.updated_at, data = excluded.data`,
			userID, m.ID, rev, updatedAt, data); err != nil {
			return 0, nil, false, err
		}
	}
	if changed {
		if _, err := tx.Exec(`UPDATE counter SET rev = ? WHERE id = 1`, rev); err != nil {
			return 0, nil, false, err
		}
	}

	rows, err := tx.Query(`SELECT rev, data FROM items WHERE user_id = ? AND rev > ? ORDER BY rev`, userID, since)
	if err != nil {
		return 0, nil, false, err
	}
	items := []json.RawMessage{}
	for rows.Next() {
		var r int64
		var data string
		if err := rows.Scan(&r, &data); err != nil {
			rows.Close()
			return 0, nil, false, err
		}
		items = append(items, injectRev(data, r))
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, nil, false, err
	}
	// The client may already have seen the winning copy's rev, so send it back
	// explicitly; otherwise it would mark its losing edit clean and diverge.
	for _, id := range losers {
		var r int64
		var data string
		if err := tx.QueryRow(`SELECT rev, data FROM items WHERE user_id = ? AND id = ? AND rev <= ?`, userID, id, since).Scan(&r, &data); err == nil {
			items = append(items, injectRev(data, r))
		} else if !errors.Is(err, sql.ErrNoRows) {
			return 0, nil, false, err
		}
	}
	if err := tx.Commit(); err != nil {
		return 0, nil, false, err
	}
	return rev, items, changed, nil
}

var errBadItem = errors.New("invalid item")

// withoutRev strips the client-supplied rev so the stored blob never carries a
// stale one; the authoritative rev lives in its own column.
func withoutRev(raw json.RawMessage) (string, error) {
	var obj map[string]json.RawMessage
	if err := json.Unmarshal(raw, &obj); err != nil {
		return "", err
	}
	delete(obj, "rev")
	b, err := json.Marshal(obj)
	return string(b), err
}

func injectRev(data string, rev int64) json.RawMessage {
	// data is a JSON object without "rev"; splice it in after the opening brace.
	if len(data) < 2 || data[0] != '{' {
		return json.RawMessage(data)
	}
	sep := ","
	if data == "{}" {
		sep = ""
	}
	return json.RawMessage(fmt.Sprintf(`{"rev":%d%s%s`, rev, sep, data[1:]))
}

// UserRev is the highest rev among a user's items (0 if none).
func (s *Store) UserRev(userID int64) (int64, error) {
	var rev sql.NullInt64
	err := s.db.QueryRow(`SELECT MAX(rev) FROM items WHERE user_id = ?`, userID).Scan(&rev)
	return rev.Int64, err
}
