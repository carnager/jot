package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeOpenAI answers image requests with a small JPEG and records the prompts.
func fakeOpenAI(t *testing.T) (*httptest.Server, func() []string) {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 48, 32))
	for x := range 48 {
		for y := range 32 {
			img.Set(x, y, color.RGBA{0, 144, 255, 255})
		}
	}
	var buf bytes.Buffer
	jpeg.Encode(&buf, img, nil)
	b64 := base64.StdEncoding.EncodeToString(buf.Bytes())
	var mu sync.Mutex
	var prompts []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/images/generations" || r.Header.Get("Authorization") != "Bearer test-key" {
			http.Error(w, `{"error":{"message":"bad request"}}`, http.StatusBadRequest)
			return
		}
		var req struct {
			Prompt string `json:"prompt"`
		}
		json.NewDecoder(r.Body).Decode(&req)
		mu.Lock()
		prompts = append(prompts, req.Prompt)
		mu.Unlock()
		fmt.Fprintf(w, `{"data":[{"b64_json":%q}]}`, b64)
	}))
	t.Cleanup(srv.Close)
	return srv, func() []string { mu.Lock(); defer mu.Unlock(); return append([]string(nil), prompts...) }
}

func testArtist(t *testing.T, auto bool) (*Artist, int64, func() []string) {
	t.Helper()
	s, uid := testStore(t)
	srv, prompts := fakeOpenAI(t)
	a := NewArtist(s, NewHub(), artConfig{key: "test-key", baseURL: srv.URL, model: "m", quality: "low", size: "1536x1024", dailyLimit: 3})
	if err := s.PutSettings(uid, Settings{"art": mustJSON(artSettings{Auto: auto})}); err != nil {
		t.Fatal(err)
	}
	return a, uid, prompts
}

func syncItem(t *testing.T, a *Artist, uid int64, item map[string]any) {
	t.Helper()
	raw := mustJSON(item)
	if _, _, _, err := a.store.Sync(uid, 0, []json.RawMessage{raw}); err != nil {
		t.Fatal(err)
	}
	a.Notice(uid, []json.RawMessage{raw})
}

func storedImages(t *testing.T, a *Artist, uid int64, id string) []string {
	t.Helper()
	it, ok, err := a.loadItem(uid, id)
	if err != nil || !ok {
		t.Fatalf("load %s: ok=%v err=%v", id, ok, err)
	}
	var ids []string
	for _, raw := range it.Images {
		var r struct {
			ID string `json:"id"`
		}
		json.Unmarshal(raw, &r)
		ids = append(ids, r.ID)
	}
	return ids
}

func drain(t *testing.T, a *Artist) int {
	t.Helper()
	n := 0
	for {
		worked, err := a.step(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		if !worked {
			return n
		}
		n++
	}
}

func TestArtAutoForNewNotes(t *testing.T) {
	a, uid, prompts := testArtist(t, true)
	now := time.Now().UnixMilli()
	syncItem(t, a, uid, map[string]any{"id": "n1", "title": "Schwimmen Daria", "labels": []string{"daria"}, "createdAt": now, "updatedAt": now})
	syncItem(t, a, uid, map[string]any{"id": "old", "title": "Imported", "createdAt": now - 3*24*3600*1000, "updatedAt": now})
	syncItem(t, a, uid, map[string]any{"id": "pic", "title": "Has a photo", "images": []any{map[string]any{"id": "x"}}, "createdAt": now, "updatedAt": now})
	syncItem(t, a, uid, map[string]any{"id": "empty", "title": " ", "createdAt": now, "updatedAt": now})

	if n := drain(t, a); n != 1 {
		t.Fatalf("processed %d jobs, want 1 (only the new note without images)", n)
	}
	imgs := storedImages(t, a, uid, "n1")
	if len(imgs) != 1 || !strings.HasPrefix(imgs[0], "art-") {
		t.Fatalf("images = %v", imgs)
	}
	if _, err := a.store.Attachment(uid, imgs[0]); err != nil {
		t.Fatalf("attachment not stored: %v", err)
	}
	if p := prompts(); len(p) != 1 || !strings.Contains(p[0], "Schwimmen Daria") || !strings.Contains(p[0], "Labels: daria") {
		t.Fatalf("prompts = %q", p)
	}

	// The user removes the picture: it must not come back.
	later := time.Now().UnixMilli() + 1000
	syncItem(t, a, uid, map[string]any{"id": "n1", "title": "Schwimmen Daria", "images": []any{}, "createdAt": now,
		"updatedAt": later, "fm": map[string]int64{"images": later}})
	if n := drain(t, a); n != 0 {
		t.Fatalf("removed picture was repainted (%d jobs)", n)
	}
	if imgs := storedImages(t, a, uid, "n1"); len(imgs) != 0 {
		t.Fatalf("images = %v after removal", imgs)
	}
}

func TestArtWaitsForTypingToSettle(t *testing.T) {
	a, uid, prompts := testArtist(t, true)
	a.cfg.delay = time.Hour
	now := time.Now().UnixMilli()
	// Sent from the capture line: complete, so it starts at once.
	syncItem(t, a, uid, map[string]any{"id": "c1", "title": "Zahnarzt", "createdAt": now, "updatedAt": now + 3})
	var captured int64
	a.store.db.QueryRow(`SELECT not_before FROM art WHERE item_id = 'c1'`).Scan(&captured)
	if captured > time.Now().UnixMilli() {
		t.Fatalf("a captured note waits until %d", captured)
	}
	a.store.db.Exec(`DELETE FROM art`)

	// Typed into the editor: waits, and every edit pushes the start back.
	syncItem(t, a, uid, map[string]any{"id": "n1", "title": "Schw", "createdAt": now - 2000, "updatedAt": now})
	var first int64
	a.store.db.QueryRow(`SELECT not_before FROM art WHERE item_id = 'n1'`).Scan(&first)
	time.Sleep(5 * time.Millisecond)
	syncItem(t, a, uid, map[string]any{"id": "n1", "title": "Schwimmen Daria", "createdAt": now - 2000, "updatedAt": now + 1})
	var second int64
	a.store.db.QueryRow(`SELECT not_before FROM art WHERE item_id = 'n1'`).Scan(&second)
	if second <= first {
		t.Fatalf("an edit didn't push the start back: %d → %d", first, second)
	}
	a.store.db.Exec(`UPDATE art SET not_before = 0`)
	drain(t, a)
	if p := prompts(); len(p) != 1 || !strings.Contains(p[0], "Schwimmen Daria") {
		t.Fatalf("prompts = %q", p)
	}
}

func TestArtOffByDefault(t *testing.T) {
	a, uid, prompts := testArtist(t, false)
	now := time.Now().UnixMilli()
	syncItem(t, a, uid, map[string]any{"id": "n1", "title": "Dentist", "createdAt": now, "updatedAt": now})
	if n := drain(t, a); n != 0 || len(prompts()) != 0 {
		t.Fatalf("painted without opting in")
	}
}

func TestArtRegenerateReplacesOnlyItsOwnPicture(t *testing.T) {
	a, uid, _ := testArtist(t, false)
	now := time.Now().UnixMilli()
	syncItem(t, a, uid, map[string]any{"id": "n1", "title": "Garden", "images": []any{map[string]any{"id": "photo"}}, "createdAt": now, "updatedAt": now})

	if err := a.Request(uid, "n1"); err != nil {
		t.Fatal(err)
	}
	drain(t, a)
	first := storedImages(t, a, uid, "n1")
	if len(first) != 2 || !strings.HasPrefix(first[0], "art-") || first[1] != "photo" {
		t.Fatalf("after first request: %v", first)
	}
	if err := a.Request(uid, "n1"); err != nil {
		t.Fatal(err)
	}
	drain(t, a)
	second := storedImages(t, a, uid, "n1")
	if len(second) != 2 || second[0] == first[0] || !strings.HasPrefix(second[0], "art-") || second[1] != "photo" {
		t.Fatalf("after regenerate: %v (before %v)", second, first)
	}

	if err := a.Request(uid, "missing"); err != errArtNoItem {
		t.Fatalf("missing item: %v", err)
	}
	a.Request(uid, "n1")
	drain(t, a)
	if err := a.Request(uid, "n1"); err != errArtLimit {
		t.Fatalf("over the daily limit: %v", err)
	}
}
