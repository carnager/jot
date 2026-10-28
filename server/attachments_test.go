package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func testPNG(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for x := range w {
		img.Set(x, 0, color.NRGBA{255, 0, 0, 128})
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestAttachmentPutThumbAndCollect(t *testing.T) {
	s, uid := testStore(t)
	a, err := s.PutAttachment(uid, "img1", "image/jpeg", testPNG(t, 1000, 500))
	if err != nil {
		t.Fatal(err)
	}
	if a.MIME != "image/png" || a.W != 1000 || a.H != 500 {
		t.Fatalf("detected %+v", a)
	}
	// Idempotent: the second upload keeps the first.
	if b, err := s.PutAttachment(uid, "img1", "image/png", testPNG(t, 10, 10)); err != nil || b.W != 1000 {
		t.Fatalf("re-put: %+v %v", b, err)
	}
	if _, err := s.PutAttachment(uid, "bad", "image/png", []byte("not an image")); err == nil {
		t.Fatal("accepted garbage")
	}

	thumb, err := s.thumbnail(uid, a, 240)
	if err != nil {
		t.Fatal(err)
	}
	f, _ := os.Open(thumb)
	cfg, format, err := image.DecodeConfig(f)
	f.Close()
	if err != nil || format != "jpeg" || cfg.Width != 240 || cfg.Height != 120 {
		t.Fatalf("thumb %s %+v %v", format, cfg, err)
	}

	// Referenced by a trashed item: kept. Referenced only by a deleted one: collected.
	s.Sync(uid, 0, raws(`{"id":"n1","updatedAt":1,"trashedAt":5,"images":[{"id":"img1"}]}`))
	s.PutAttachment(uid, "img2", "image/png", testPNG(t, 4, 4))
	s.Sync(uid, 0, raws(`{"id":"n2","updatedAt":1,"deleted":true,"images":[{"id":"img2"}]}`))
	s.PutAttachment(uid, "fresh", "image/png", testPNG(t, 4, 4))
	s.db.Exec(`UPDATE attachments SET created = ? WHERE id != 'fresh'`, time.Now().Add(-48*time.Hour).UnixMilli())

	n, err := s.CollectAttachments(24 * time.Hour)
	if err != nil || n != 1 {
		t.Fatalf("collected %d, %v", n, err)
	}
	if _, err := s.Attachment(uid, "img2"); err != ErrNotFound {
		t.Fatal("img2 should be gone")
	}
	for _, id := range []string{"img1", "fresh"} {
		if _, err := s.Attachment(uid, id); err != nil {
			t.Fatalf("%s should be kept: %v", id, err)
		}
	}
	if _, err := os.Stat(filepath.Join(s.attachmentDir(uid), "img2")); !os.IsNotExist(err) {
		t.Fatal("img2 file still on disk")
	}
}
