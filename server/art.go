package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// Illustrations (SPEC §2d): with an OpenAI key configured, the server paints a picture for new
// notes of users who opted in, and on request for any note. The picture is an ordinary attachment
// added to the item's images, so every client shows it without knowing where it came from.

const artSchema = `
CREATE TABLE IF NOT EXISTS art (
	user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	item_id    TEXT NOT NULL,
	state      TEXT NOT NULL,              -- pending | done | skipped | failed | limited
	forced     INTEGER NOT NULL DEFAULT 0, -- asked for explicitly (regenerate), not automatic
	attachment TEXT NOT NULL DEFAULT '',   -- the last generated image, replaced on regenerate
	not_before INTEGER NOT NULL,
	tries      INTEGER NOT NULL DEFAULT 0,
	PRIMARY KEY (user_id, item_id)
);
CREATE TABLE IF NOT EXISTS art_log (
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	at      INTEGER NOT NULL
);`

const defaultArtStyle = "A cheerful flat vector illustration with clean shapes, bright saturated colors and soft light, " +
	"like a modern editorial spot illustration."

type artConfig struct {
	key, baseURL, model, quality, size string
	textModel                          string // picks the characters for a note and describes the scene
	fidelity                           string // input_fidelity for reference images; "" = don't send (most models reject it)
	dailyLimit                         int
	delay                              time.Duration // quiet time after a note's last change, so its text can settle
}

// artConfigFromEnv returns the configuration, or ok=false when no key is set (the feature is off).
func artConfigFromEnv() (cfg artConfig, ok bool) {
	cfg = artConfig{
		key:        artEnv("JOT_OPENAI_KEY", artEnv("OPENAI_API_KEY", "")),
		baseURL:    strings.TrimRight(artEnv("JOT_OPENAI_URL", "https://api.openai.com/v1"), "/"),
		model:      artEnv("JOT_ART_MODEL", "gpt-image-1-mini"),
		quality:    artEnv("JOT_ART_QUALITY", "medium"),
		size:       artEnv("JOT_ART_SIZE", "1536x1024"),
		textModel:  artEnv("JOT_ART_TEXT_MODEL", "gpt-5.4-mini"),
		fidelity:   artEnv("JOT_ART_FIDELITY", ""),
		dailyLimit: 25,
		delay:      4 * time.Second,
	}
	if n, err := strconv.Atoi(artEnv("JOT_ART_DAILY_LIMIT", "")); err == nil && n >= 0 {
		cfg.dailyLimit = n
	}
	return cfg, cfg.key != ""
}

// artEnv reads a setting without a trailing "# comment": systemd's env files keep those as part
// of the value, which once turned the model name into "gpt-image-1-mini   # or gpt-image-1".
func artEnv(key, def string) string {
	v, _, _ := strings.Cut(os.Getenv(key), "#")
	if v = strings.TrimSpace(v); v != "" {
		return v
	}
	return def
}

type artSettings struct {
	Auto       bool           `json:"auto"`
	Style      string         `json:"style"`
	Characters []artCharacter `json:"characters"`
}

// artCharacter is a recurring figure with a reference image (an attachment id), drawn in fitting notes.
type artCharacter struct {
	Name  string `json:"name"`
	About string `json:"about"` // what it looks like, and which notes it suits
	Image string `json:"image"`
}

const maxArtCharacters = 6

func (s Settings) art() artSettings {
	var a artSettings
	json.Unmarshal(s["art"], &a)
	return a
}

type Artist struct {
	store  *Store
	hub    *Hub
	cfg    artConfig
	client *http.Client
	wake   chan struct{}
}

func NewArtist(store *Store, hub *Hub, cfg artConfig) *Artist {
	return &Artist{store: store, hub: hub, cfg: cfg, client: &http.Client{Timeout: 3 * time.Minute}, wake: make(chan struct{}, 1)}
}

func (a *Artist) poke() {
	select {
	case a.wake <- struct{}{}:
	default:
	}
}

// artItem is what the artist reads from an item.
type artItem struct {
	Title  string   `json:"title"`
	Body   string   `json:"body"`
	Labels []string `json:"labels"`
	Checks []struct {
		Text    string `json:"text"`
		Deleted bool   `json:"deleted"`
	} `json:"checks"`
	Images    []json.RawMessage `json:"images"`
	CreatedAt int64             `json:"createdAt"`
	UpdatedAt int64             `json:"updatedAt"`
	Archived  bool              `json:"archived"`
	Done      bool              `json:"done"`
	TrashedAt int64             `json:"trashedAt"`
	Deleted   bool              `json:"deleted"`
}

func (it artItem) hasText() bool {
	if strings.TrimSpace(it.Title) != "" || strings.TrimSpace(it.Body) != "" {
		return true
	}
	for _, c := range it.Checks {
		if !c.Deleted && strings.TrimSpace(c.Text) != "" {
			return true
		}
	}
	return false
}

// Notice queues an automatic illustration for new, image-less notes in a sync's changes.
// Each item gets at most one automatic attempt, so removing the picture keeps it removed.
func (a *Artist) Notice(userID int64, changes []json.RawMessage) {
	set, err := a.store.Settings(userID)
	if err != nil || !set.art().Auto {
		return
	}
	var enabledAt int64
	json.Unmarshal(set["artEnabledAt"], &enabledAt)
	now := time.Now()
	queued := false
	for _, raw := range changes {
		var it artItem
		var meta ItemMeta
		if json.Unmarshal(raw, &it) != nil || json.Unmarshal(raw, &meta) != nil {
			continue
		}
		// Only notes created since auto was turned on, within the last day (offline captures
		// count; old notes and Keep imports don't).
		if it.CreatedAt < enabledAt || it.CreatedAt < now.Add(-24*time.Hour).UnixMilli() {
			continue
		}
		if len(it.Images) > 0 || it.Deleted || it.TrashedAt > 0 || it.Archived || it.Done || !it.hasText() {
			continue
		}
		// A note sent from the capture line is saved in one go (updatedAt ≈ createdAt) and complete, so it's
		// painted right away. One typed into the editor after it was created waits until it settles: every
		// change while it waits pushes the start back. Notes that had their turn (done, skipped, removed) are left alone.
		start := now.Add(a.cfg.delay)
		if it.UpdatedAt-it.CreatedAt < 1000 {
			start = now
		}
		res, err := a.store.db.Exec(`INSERT INTO art (user_id, item_id, state, not_before) VALUES (?, ?, 'pending', ?)
			ON CONFLICT (user_id, item_id) DO UPDATE SET not_before = excluded.not_before WHERE art.state = 'pending' AND art.forced = 0`,
			userID, meta.ID, start.UnixMilli())
		if err != nil {
			log.Printf("art: queue: %v", err)
			continue
		}
		if n, _ := res.RowsAffected(); n > 0 {
			queued = true
		}
	}
	if queued {
		a.poke()
	}
}

var (
	errArtLimit     = errors.New("daily image limit reached")
	errArtNoItem    = errors.New("no such item")
	errArtPermanent = errors.New("image request rejected")
)

// Request queues an illustration for an item right away, replacing the previous one if it's still there.
func (a *Artist) Request(userID int64, itemID string) error {
	var data string
	if err := a.store.db.QueryRow(`SELECT data FROM items WHERE user_id = ? AND id = ?`, userID, itemID).Scan(&data); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return errArtNoItem
		}
		return err
	}
	if n, err := a.usedToday(userID); err != nil {
		return err
	} else if n >= a.cfg.dailyLimit {
		return errArtLimit
	}
	_, err := a.store.db.Exec(`INSERT INTO art (user_id, item_id, state, forced, not_before) VALUES (?, ?, 'pending', 1, ?)
		ON CONFLICT (user_id, item_id) DO UPDATE SET state = 'pending', forced = 1, not_before = excluded.not_before, tries = 0`,
		userID, itemID, time.Now().UnixMilli())
	if err == nil {
		a.poke()
	}
	return err
}

func (a *Artist) usedToday(userID int64) (int, error) {
	var n int
	err := a.store.db.QueryRow(`SELECT COUNT(*) FROM art_log WHERE user_id = ? AND at > ?`,
		userID, time.Now().Add(-24*time.Hour).UnixMilli()).Scan(&n)
	return n, err
}

type artJob struct {
	userID     int64
	itemID     string
	forced     bool
	attachment string
	tries      int
	notBefore  int64
}

// Run works through the queue, one picture at a time.
func (a *Artist) Run(ctx context.Context) {
	for {
		worked, err := a.step(ctx)
		if err != nil {
			log.Printf("art: %v", err)
		}
		if worked && err == nil {
			continue
		}
		wait := time.Minute
		var next sql.NullInt64
		if a.store.db.QueryRow(`SELECT MIN(not_before) FROM art WHERE state = 'pending'`).Scan(&next) == nil && next.Valid {
			wait = min(wait, max(time.Until(time.UnixMilli(next.Int64)), 50*time.Millisecond))
		}
		select {
		case <-ctx.Done():
			return
		case <-a.wake:
		case <-time.After(wait):
		}
	}
}

// step processes the next due job. It reports whether there was one.
func (a *Artist) step(ctx context.Context) (bool, error) {
	var j artJob
	var forced int
	err := a.store.db.QueryRow(`SELECT user_id, item_id, forced, attachment, tries, not_before FROM art
		WHERE state = 'pending' AND not_before <= ? ORDER BY not_before LIMIT 1`, time.Now().UnixMilli()).
		Scan(&j.userID, &j.itemID, &forced, &j.attachment, &j.tries, &j.notBefore)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	j.forced = forced == 1
	state, attachment, err := a.paint(ctx, j)
	switch {
	case err == nil:
	case errors.Is(err, errArtPermanent) || j.tries >= 3:
		log.Printf("art: item %s: %v", j.itemID, err)
		state = "failed"
	default:
		log.Printf("art: item %s: %v (will retry)", j.itemID, err)
		_, err = a.store.db.Exec(`UPDATE art SET tries = tries + 1, not_before = ? WHERE user_id = ? AND item_id = ? AND not_before = ?`,
			time.Now().Add(time.Minute<<j.tries).UnixMilli(), j.userID, j.itemID, j.notBefore)
		return true, err
	}
	// A regenerate request that came in meanwhile moved not_before; leave that one pending.
	_, err = a.store.db.Exec(`UPDATE art SET state = ?, attachment = ? WHERE user_id = ? AND item_id = ? AND not_before = ?`,
		state, attachment, j.userID, j.itemID, j.notBefore)
	return true, err
}

// paint generates and attaches a picture. It returns the job's final state and the attachment
// that the job now remembers (the old one when nothing was painted).
func (a *Artist) paint(ctx context.Context, j artJob) (string, string, error) {
	it, ok, err := a.loadItem(j.userID, j.itemID)
	if err != nil {
		return "", j.attachment, err
	}
	if !ok || it.Deleted || it.TrashedAt > 0 || !it.hasText() {
		return "skipped", j.attachment, nil
	}
	set, err := a.store.Settings(j.userID)
	if err != nil {
		return "", j.attachment, err
	}
	if !j.forced && (len(it.Images) > 0 || !set.art().Auto) {
		return "skipped", j.attachment, nil
	}
	if n, err := a.usedToday(j.userID); err != nil {
		return "", j.attachment, err
	} else if n >= a.cfg.dailyLimit {
		return "limited", j.attachment, nil
	}

	img, err := a.draw(ctx, j.userID, set.art(), it)
	if err != nil {
		return "", j.attachment, err
	}
	if _, err := a.store.db.Exec(`INSERT INTO art_log (user_id, at) VALUES (?, ?)`, j.userID, time.Now().UnixMilli()); err != nil {
		return "", j.attachment, err
	}
	att, err := a.store.PutAttachment(j.userID, "art-"+randomToken()[:20], "image/jpeg", img)
	if err != nil {
		return "", j.attachment, err
	}
	attached, err := a.attach(j, att)
	if err != nil {
		return "", j.attachment, err
	}
	if !attached {
		return "skipped", j.attachment, nil // the unreferenced picture is collected later
	}
	return "done", att.ID, nil
}

func (a *Artist) loadItem(userID int64, itemID string) (artItem, bool, error) {
	var it artItem
	var data string
	err := a.store.db.QueryRow(`SELECT data FROM items WHERE user_id = ? AND id = ?`, userID, itemID).Scan(&data)
	if errors.Is(err, sql.ErrNoRows) {
		return it, false, nil
	}
	if err != nil {
		return it, false, err
	}
	return it, true, json.Unmarshal([]byte(data), &it)
}

// attach puts the picture into the item's images through the normal sync path, so it merges with
// edits made meanwhile (SPEC §2b). It reports false when the item no longer wants it.
func (a *Artist) attach(j artJob, att Attachment) (bool, error) {
	var data string
	if err := a.store.db.QueryRow(`SELECT data FROM items WHERE user_id = ? AND id = ?`, j.userID, j.itemID).Scan(&data); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}
		return false, err
	}
	var item map[string]json.RawMessage
	if err := json.Unmarshal([]byte(data), &item); err != nil {
		return false, err
	}
	if isTrue(item["deleted"]) {
		return false, nil
	}
	var images []json.RawMessage
	json.Unmarshal(item["images"], &images)
	ref := mustJSON(map[string]any{"id": att.ID, "w": att.W, "h": att.H, "mime": att.MIME})
	replaced := false
	if j.forced && j.attachment != "" {
		for i, raw := range images {
			var r struct {
				ID string `json:"id"`
			}
			if json.Unmarshal(raw, &r) == nil && r.ID == j.attachment {
				images[i] = ref
				replaced = true
				break
			}
		}
	}
	switch {
	case replaced:
	case j.forced:
		images = append([]json.RawMessage{ref}, images...)
	case len(images) > 0:
		return false, nil // the user added a picture of their own while we painted
	default:
		images = []json.RawMessage{ref}
	}
	nowMs := time.Now().UnixMilli()
	item["images"] = mustJSON(images)
	var fm map[string]int64
	json.Unmarshal(item["fm"], &fm)
	if fm == nil {
		fm = map[string]int64{}
	}
	fm["images"] = nowMs
	item["fm"] = mustJSON(fm)
	item["updatedAt"] = mustJSON(nowMs)
	raw, err := json.Marshal(item)
	if err != nil {
		return false, err
	}
	rev, _, changed, err := a.store.Sync(j.userID, 1<<62, []json.RawMessage{raw})
	if err != nil {
		return false, err
	}
	if changed {
		a.hub.Publish(j.userID, rev)
	}
	return true, nil
}

// draw makes the picture. With characters set up, a text model first picks the ones that fit the
// note and describes a scene, and only their reference images go to the image model.
func (a *Artist) draw(ctx context.Context, userID int64, set artSettings, it artItem) ([]byte, error) {
	var cast []artCharacter
	var refs []artRef
	for i, c := range set.Characters {
		if len(cast) == maxArtCharacters {
			break
		}
		// A character whose name didn't get saved still counts; the models need something to call it.
		if c.Name = strings.TrimSpace(c.Name); c.Name == "" {
			c.Name = fmt.Sprintf("Character %d", i+1)
		}
		if ref, err := a.loadRef(userID, c.Image); err == nil {
			cast, refs = append(cast, c), append(refs, ref)
		}
	}
	if len(cast) == 0 {
		return a.generate(ctx, artPrompt(set.Style, it, nil, ""))
	}
	picked, scene, err := a.choose(ctx, cast, it)
	if err != nil {
		// Without a plan (even with a wrong text model) the image model gets everyone and is told to pick.
		log.Printf("art: choosing characters: %v", err)
		picked, scene = nil, ""
		for i := range cast {
			picked = append(picked, i)
		}
	}
	var chosen []artCharacter
	var images []artRef
	for _, i := range picked {
		chosen, images = append(chosen, cast[i]), append(images, refs[i])
	}
	return a.edit(ctx, artPrompt(set.Style, it, chosen, scene), images)
}

type artRef struct {
	data []byte
	mime string
}

// loadRef reads a character's reference image, at most 1600 px wide: references are often mostly
// background, and at 960 px the character itself got too small to keep its detail.
func (a *Artist) loadRef(userID int64, id string) (artRef, error) {
	att, err := a.store.Attachment(userID, id)
	if err != nil {
		return artRef{}, err
	}
	path, mime := filepath.Join(a.store.attachmentDir(userID), att.ID), att.MIME
	if att.MIME == "image/gif" || att.W > 1600 {
		if path, err = a.store.thumbnail(userID, att, 1600); err != nil {
			return artRef{}, err
		}
		mime = "image/jpeg"
	}
	data, err := os.ReadFile(path)
	return artRef{data, mime}, err
}

// choose asks the text model which characters belong in the note's picture, and for a scene.
func (a *Artist) choose(ctx context.Context, cast []artCharacter, it artItem) ([]int, string, error) {
	var sys strings.Builder
	sys.WriteString("You plan small illustrations for notes and tasks in a personal notes app. " +
		"Every picture shows one or more of these recurring characters:\n")
	for _, c := range cast {
		fmt.Fprintf(&sys, "- %s: %s\n", c.Name, clip(strings.TrimSpace(c.About), 600))
	}
	sys.WriteString("\nFor the note you get, choose the characters that fit it best: usually one, several or all of them only " +
		"when the note is about something done together or the characters' descriptions call for it. Then describe, in English, " +
		"in one to three sentences, a concrete scene with them doing what the note is about. The note may be in German or English. " +
		`Answer with JSON only: {"characters": ["Name", ...], "scene": "..."}`)
	body, _ := json.Marshal(map[string]any{
		"model": a.cfg.textModel,
		"messages": []map[string]string{
			{"role": "system", "content": sys.String()},
			{"role": "user", "content": noteText(it)},
		},
		"response_format": map[string]string{"type": "json_object"},
	})
	raw, err := a.call(ctx, "/chat/completions", "application/json", bytes.NewReader(body))
	if err != nil {
		return nil, "", err
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
	}
	var plan struct {
		Characters []string `json:"characters"`
		Scene      string   `json:"scene"`
	}
	if json.Unmarshal(raw, &out) != nil || len(out.Choices) == 0 || json.Unmarshal([]byte(out.Choices[0].Message.Content), &plan) != nil {
		return nil, "", errors.New("openai: unreadable plan")
	}
	var picked []int
	for i, c := range cast {
		for _, name := range plan.Characters {
			if strings.EqualFold(strings.TrimSpace(name), strings.TrimSpace(c.Name)) {
				picked = append(picked, i)
				break
			}
		}
	}
	if len(picked) == 0 {
		return nil, "", fmt.Errorf("openai: plan names no known character (%q)", plan.Characters)
	}
	return picked, strings.TrimSpace(plan.Scene), nil
}

// artPrompt describes the note to the image model, wrapped in the house style. With characters, their
// reference images come in the same order, and scene (when the text model wrote one) says what they do.
func artPrompt(style string, it artItem, cast []artCharacter, scene string) string {
	style = strings.TrimSpace(style)
	if style == "" {
		style = defaultArtStyle
	}
	var b strings.Builder
	if len(cast) > 0 {
		// The references lead: they're the look to match. The style only sets the scene's mood.
		b.WriteString("The reference images show recurring characters from one animated film. Draw them so they are " +
			"unmistakably the same characters: the same rendering (3D or 2D, textures such as fur and fabric, shading, lighting " +
			"and level of detail), the same proportions, faces, eyes, colors, patterns and clothing. Copy their look, don't " +
			"reinterpret or simplify it. Put them in a new scene and don't copy the reference backgrounds.\n")
	}
	b.WriteString(style)
	b.WriteString(" It decorates a card in a personal notes app and should show at a glance what the note is about. " +
		"The note may be in German or English. Depict the scene, activity or objects it is about. " +
		"Do not put any text, letters, numbers or words in the image. No borders, frames or user interface.\n\n")
	if len(cast) > 0 {
		for i, c := range cast {
			fmt.Fprintf(&b, "Image %d: %s", i+1, c.Name)
			if about := strings.TrimSpace(c.About); about != "" {
				fmt.Fprintf(&b, " (%s)", clip(about, 600))
			}
			b.WriteString("\n")
		}
		if scene != "" {
			fmt.Fprintf(&b, "Show exactly these characters in this scene: %s\n\n", scene)
		} else {
			b.WriteString("Show only the characters that fit the note, usually one.\n\n")
		}
	}
	b.WriteString(noteText(it))
	return b.String()
}

// noteText is the note's content as the models see it.
func noteText(it artItem) string {
	var b strings.Builder
	if t := strings.TrimSpace(it.Title); t != "" {
		fmt.Fprintf(&b, "Note title: %s\n", clip(t, 200))
	}
	if body := strings.TrimSpace(it.Body); body != "" {
		fmt.Fprintf(&b, "Note text: %s\n", clip(body, 600))
	}
	var checks []string
	for _, c := range it.Checks {
		if t := strings.TrimSpace(c.Text); !c.Deleted && t != "" && len(checks) < 12 {
			checks = append(checks, clip(t, 80))
		}
	}
	if len(checks) > 0 {
		fmt.Fprintf(&b, "Checklist: %s\n", strings.Join(checks, "; "))
	}
	if len(it.Labels) > 0 {
		fmt.Fprintf(&b, "Labels: %s\n", strings.Join(it.Labels, ", "))
	}
	return b.String()
}

// edit calls the Images edit endpoint with reference images and returns JPEG bytes.
func (a *Artist) edit(ctx context.Context, prompt string, refs []artRef) ([]byte, error) {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fields := map[string]string{
		"model": a.cfg.model, "prompt": prompt, "n": "1", "size": a.cfg.size, "quality": a.cfg.quality,
		"output_format": "jpeg", "output_compression": "85",
	}
	// Only some models take input_fidelity (gpt-image-1-mini doesn't, despite the docs), so it's opt-in.
	if a.cfg.fidelity != "" {
		fields["input_fidelity"] = a.cfg.fidelity
	}
	for k, v := range fields {
		mw.WriteField(k, v)
	}
	for i, r := range refs {
		h := textproto.MIMEHeader{}
		ext := strings.TrimPrefix(r.mime, "image/")
		h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="image[]"; filename="character-%d.%s"`, i+1, ext))
		h.Set("Content-Type", r.mime)
		w, err := mw.CreatePart(h)
		if err != nil {
			return nil, err
		}
		w.Write(r.data)
	}
	if err := mw.Close(); err != nil {
		return nil, err
	}
	raw, err := a.call(ctx, "/images/edits", mw.FormDataContentType(), &body)
	if err != nil {
		return nil, err
	}
	return decodeImage(raw)
}

func clip(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n]) + "…"
}

// generate calls the OpenAI Images API and returns JPEG bytes.
func (a *Artist) generate(ctx context.Context, prompt string) ([]byte, error) {
	body, _ := json.Marshal(map[string]any{
		"model": a.cfg.model, "prompt": prompt, "n": 1, "size": a.cfg.size, "quality": a.cfg.quality,
		"output_format": "jpeg", "output_compression": 85,
	})
	raw, err := a.call(ctx, "/images/generations", "application/json", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	return decodeImage(raw)
}

// call POSTs to the OpenAI API and returns the response body of a successful call.
func (a *Artist) call(ctx context.Context, path, contentType string, body io.Reader) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.cfg.baseURL+path, body)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+a.cfg.key)
	req.Header.Set("Content-Type", contentType)
	resp, err := a.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		var out struct {
			Error *struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		json.Unmarshal(raw, &out)
		msg := resp.Status
		if out.Error != nil && out.Error.Message != "" {
			msg = out.Error.Message
		}
		// Rate limits and server trouble pass; anything else (bad key, refused prompt) won't.
		if resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode >= 500 {
			return nil, fmt.Errorf("openai: %s", msg)
		}
		return nil, fmt.Errorf("%w: openai: %s", errArtPermanent, msg)
	}
	return raw, nil
}

func decodeImage(raw []byte) ([]byte, error) {
	var out struct {
		Data []struct {
			B64 string `json:"b64_json"`
		} `json:"data"`
	}
	json.Unmarshal(raw, &out)
	if len(out.Data) == 0 || out.Data[0].B64 == "" {
		return nil, errors.New("openai: no image in response")
	}
	return base64.StdEncoding.DecodeString(out.Data[0].B64)
}

// ---- handler ----

// handleArt (POST /api/items/{id}/art) paints a new picture for an item now.
func (s *Server) handleArt(w http.ResponseWriter, r *http.Request) {
	if s.art == nil {
		writeErr(w, http.StatusServiceUnavailable, "image generation is not set up on this server (JOT_OPENAI_KEY)")
		return
	}
	err := s.art.Request(userFrom(r).ID, r.PathValue("id"))
	switch {
	case err == nil:
		writeJSON(w, http.StatusAccepted, map[string]bool{"ok": true})
	case errors.Is(err, errArtNoItem):
		writeErr(w, http.StatusNotFound, "no such item (not synced yet?)")
	case errors.Is(err, errArtLimit):
		writeErr(w, http.StatusTooManyRequests, fmt.Sprintf("daily limit of %d images reached", s.art.cfg.dailyLimit))
	default:
		log.Printf("art request: %v", err)
		writeErr(w, http.StatusInternalServerError, "could not queue the image")
	}
}
