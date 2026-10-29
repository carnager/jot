package main

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"sync"
	"time"

	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

const maxAttachmentBytes = 25 << 20

var (
	attachmentID     = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
	attachmentMIMEs  = map[string]bool{"image/jpeg": true, "image/png": true, "image/webp": true, "image/gif": true}
	thumbWidths      = map[int]bool{240: true, 480: true, 960: true, 1600: true}
	thumbGenerations sync.Mutex // decoding big images is memory hungry; one at a time is plenty
)

type Attachment struct {
	ID   string `json:"id"`
	W    int    `json:"w"`
	H    int    `json:"h"`
	MIME string `json:"mime"`
	Size int64  `json:"size"`
}

const attachmentSchema = `
CREATE TABLE IF NOT EXISTS attachments (
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	id      TEXT NOT NULL,
	mime    TEXT NOT NULL,
	w       INTEGER NOT NULL,
	h       INTEGER NOT NULL,
	size    INTEGER NOT NULL,
	created INTEGER NOT NULL,
	PRIMARY KEY (user_id, id)
);`

// attachmentDir holds a user's originals; thumbnails live in a thumbs/ subdirectory.
func (s *Store) attachmentDir(userID int64) string {
	return filepath.Join(s.dir, "attachments", strconv.FormatInt(userID, 10))
}

func (s *Store) Attachment(userID int64, id string) (Attachment, error) {
	a := Attachment{ID: id}
	err := s.db.QueryRow(`SELECT mime, w, h, size FROM attachments WHERE user_id = ? AND id = ?`, userID, id).
		Scan(&a.MIME, &a.W, &a.H, &a.Size)
	if errors.Is(err, sql.ErrNoRows) {
		return a, ErrNotFound
	}
	return a, err
}

// PutAttachment stores an image. It is idempotent: an existing id is left untouched.
func (s *Store) PutAttachment(userID int64, id, mime string, data []byte) (Attachment, error) {
	if a, err := s.Attachment(userID, id); err == nil {
		return a, nil
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return Attachment{}, fmt.Errorf("%w: not a readable image", errBadAttachment)
	}
	if detected := "image/" + format; attachmentMIMEs[detected] {
		mime = detected // trust the bytes over the header
	}
	if cfg.Width*cfg.Height > 80_000_000 {
		return Attachment{}, fmt.Errorf("%w: image dimensions too large", errBadAttachment)
	}
	dir := s.attachmentDir(userID)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return Attachment{}, err
	}
	tmp, err := os.CreateTemp(dir, ".upload-*")
	if err != nil {
		return Attachment{}, err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return Attachment{}, err
	}
	if err := tmp.Close(); err != nil {
		return Attachment{}, err
	}
	if err := os.Rename(tmp.Name(), filepath.Join(dir, id)); err != nil {
		return Attachment{}, err
	}
	a := Attachment{ID: id, W: cfg.Width, H: cfg.Height, MIME: mime, Size: int64(len(data))}
	_, err = s.db.Exec(`INSERT OR IGNORE INTO attachments (user_id, id, mime, w, h, size, created) VALUES (?, ?, ?, ?, ?, ?, ?)`,
		userID, id, a.MIME, a.W, a.H, a.Size, time.Now().UnixMilli())
	return a, err
}

var errBadAttachment = errors.New("invalid attachment")

// thumbnail returns the path of a JPEG at most width px wide, generating it on first use.
func (s *Store) thumbnail(userID int64, a Attachment, width int) (string, error) {
	dir := s.attachmentDir(userID)
	if a.W <= width && a.MIME == "image/jpeg" {
		return filepath.Join(dir, a.ID), nil // already small enough
	}
	thumb := filepath.Join(dir, "thumbs", fmt.Sprintf("%s-%d.jpg", a.ID, width))
	if _, err := os.Stat(thumb); err == nil {
		return thumb, nil
	}
	thumbGenerations.Lock()
	defer thumbGenerations.Unlock()
	if _, err := os.Stat(thumb); err == nil {
		return thumb, nil
	}
	f, err := os.Open(filepath.Join(dir, a.ID))
	if err != nil {
		return "", err
	}
	src, _, err := image.Decode(f)
	f.Close()
	if err != nil {
		return "", err
	}
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	if w > width {
		h = max(1, h*width/w)
		w = width
	}
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	// Flatten transparency onto white so PNG screenshots don't turn black as JPEG.
	draw.Draw(dst, dst.Bounds(), image.White, image.Point{}, draw.Src)
	draw.CatmullRom.Scale(dst, dst.Bounds(), src, b, draw.Over, nil)
	if err := os.MkdirAll(filepath.Dir(thumb), 0o700); err != nil {
		return "", err
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, dst, &jpeg.Options{Quality: 82}); err != nil {
		return "", err
	}
	if err := os.WriteFile(thumb+".tmp", buf.Bytes(), 0o600); err != nil {
		return "", err
	}
	return thumb, os.Rename(thumb+".tmp", thumb)
}

// CollectAttachments deletes attachments older than minAge that no item
// references. Trashed items still count; deleted (tombstoned) ones don't.
func (s *Store) CollectAttachments(minAge time.Duration) (int, error) {
	referenced := map[[2]string]bool{}
	rows, err := s.db.Query(`SELECT user_id, data FROM items WHERE data LIKE '%"images"%'`)
	if err != nil {
		return 0, err
	}
	for rows.Next() {
		var uid int64
		var data string
		if err := rows.Scan(&uid, &data); err != nil {
			rows.Close()
			return 0, err
		}
		var it struct {
			Deleted bool `json:"deleted"`
			Images  []struct {
				ID string `json:"id"`
			} `json:"images"`
		}
		if json.Unmarshal([]byte(data), &it) != nil || it.Deleted {
			continue
		}
		for _, img := range it.Images {
			referenced[[2]string{strconv.FormatInt(uid, 10), img.ID}] = true
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	// Illustration characters' reference images live in the settings (SPEC §2d).
	rows, err = s.db.Query(`SELECT user_id, data FROM settings WHERE data LIKE '%"characters"%'`)
	if err != nil {
		return 0, err
	}
	for rows.Next() {
		var uid int64
		var data string
		if err := rows.Scan(&uid, &data); err != nil {
			rows.Close()
			return 0, err
		}
		var set Settings
		if json.Unmarshal([]byte(data), &set) != nil {
			continue
		}
		for _, c := range set.art().Characters {
			referenced[[2]string{strconv.FormatInt(uid, 10), c.Image}] = true
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}

	cutoff := time.Now().Add(-minAge).UnixMilli()
	rows, err = s.db.Query(`SELECT user_id, id FROM attachments WHERE created < ?`, cutoff)
	if err != nil {
		return 0, err
	}
	var orphans [][2]string
	for rows.Next() {
		var uid int64
		var id string
		if err := rows.Scan(&uid, &id); err != nil {
			rows.Close()
			return 0, err
		}
		if key := [2]string{strconv.FormatInt(uid, 10), id}; !referenced[key] {
			orphans = append(orphans, key)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	for _, o := range orphans {
		uid, _ := strconv.ParseInt(o[0], 10, 64)
		dir := s.attachmentDir(uid)
		os.Remove(filepath.Join(dir, o[1]))
		thumbs, _ := filepath.Glob(filepath.Join(dir, "thumbs", o[1]+"-*.jpg"))
		for _, t := range thumbs {
			os.Remove(t)
		}
		if _, err := s.db.Exec(`DELETE FROM attachments WHERE user_id = ? AND id = ?`, uid, o[1]); err != nil {
			return 0, err
		}
	}
	return len(orphans), nil
}

func (s *Server) collectAttachmentsLoop() {
	for {
		if n, err := s.store.CollectAttachments(24 * time.Hour); err != nil {
			log.Printf("attachment cleanup: %v", err)
		} else if n > 0 {
			log.Printf("attachment cleanup: removed %d unreferenced images", n)
		}
		time.Sleep(6 * time.Hour)
	}
}

// ---- handlers ----

func (s *Server) handlePutAttachment(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !attachmentID.MatchString(id) {
		writeErr(w, http.StatusBadRequest, "invalid attachment id")
		return
	}
	mime := r.Header.Get("Content-Type")
	if !attachmentMIMEs[mime] {
		writeErr(w, http.StatusUnsupportedMediaType, "expected image/jpeg, image/png, image/webp or image/gif")
		return
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxAttachmentBytes))
	if err != nil {
		writeErr(w, http.StatusRequestEntityTooLarge, "image larger than 25 MB")
		return
	}
	a, err := s.store.PutAttachment(userFrom(r).ID, id, mime, data)
	if err != nil {
		if errors.Is(err, errBadAttachment) {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		log.Printf("attachment put: %v", err)
		writeErr(w, http.StatusInternalServerError, "could not store image")
		return
	}
	writeJSON(w, http.StatusOK, a)
}

func (s *Server) handleGetAttachment(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	uid := userFrom(r).ID
	a, err := s.store.Attachment(uid, id)
	if err != nil {
		if !errors.Is(err, ErrNotFound) {
			log.Printf("attachment get: %v", err)
		}
		writeErr(w, http.StatusNotFound, "no such image")
		return
	}
	path := filepath.Join(s.store.attachmentDir(uid), a.ID)
	mime := a.MIME
	if ws := r.URL.Query().Get("w"); ws != "" {
		width, _ := strconv.Atoi(ws)
		if !thumbWidths[width] {
			writeErr(w, http.StatusBadRequest, "w must be 240, 480, 960 or 1600")
			return
		}
		if a.MIME != "image/gif" { // keep GIFs animated
			if path, err = s.store.thumbnail(uid, a, width); err != nil {
				log.Printf("thumbnail %s: %v", id, err)
				writeErr(w, http.StatusInternalServerError, "could not render thumbnail")
				return
			}
			mime = "image/jpeg"
		}
	}
	f, err := os.Open(path)
	if err != nil {
		writeErr(w, http.StatusNotFound, "no such image")
		return
	}
	defer f.Close()
	h := w.Header()
	h.Set("Content-Type", mime)
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", time.Time{}, f)
}
