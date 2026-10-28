package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestImportKeep(t *testing.T) {
	s, uid := testStore(t)
	dir := filepath.Join(t.TempDir(), "Takeout", "Keep")
	os.MkdirAll(dir, 0o755)
	os.WriteFile(filepath.Join(dir, "Shopping.json"), []byte(`{"color":"YELLOW","isTrashed":false,"isPinned":true,"isArchived":false,
		"title":"Shopping","userEditedTimestampUsec":1700000000000000,"createdTimestampUsec":1690000000000000,
		"labels":[{"name":"Home Stuff"}],"listContent":[{"text":"Milk","isChecked":false},{"text":"Eggs","isChecked":true}]}`), 0o644)
	os.WriteFile(filepath.Join(dir, "photo.png"), testPNG(t, 20, 10), 0o644)
	os.WriteFile(filepath.Join(dir, "Idea.json"), []byte(`{"color":"DEFAULT","title":"","textContent":"An idea",
		"attachments":[{"filePath":"photo.png","mimetype":"image/png"},{"filePath":"voice.3gp","mimetype":"audio/3gp"}],
		"userEditedTimestampUsec":1700000000000000,"createdTimestampUsec":1700000000000000,"isTrashed":true}`), 0o644)
	os.WriteFile(filepath.Join(dir, "Labels.json"), []byte(`{}`), 0o644)

	for range 2 { // importing twice must not duplicate
		if err := importKeep(s, "alice", filepath.Dir(dir)); err != nil {
			t.Fatal(err)
		}
	}
	_, items, _, err := s.Sync(uid, 0, nil)
	if err != nil {
		t.Fatal(err)
	}
	got := decode(t, items)
	if len(got) != 2 {
		t.Fatalf("expected 2 items, got %d", len(got))
	}
	byTitle := map[string]map[string]any{}
	for _, it := range got {
		byTitle[it["title"].(string)] = it
	}
	shop := byTitle["Shopping"]
	if shop["kind"] != "list" || shop["color"] != "yellow" || shop["pinned"] != true || len(shop["checks"].([]any)) != 2 {
		t.Fatalf("shopping: %v", shop)
	}
	if labels := shop["labels"].([]any); len(labels) != 1 || labels[0] != "home-stuff" {
		t.Fatalf("labels: %v", labels)
	}
	idea := byTitle[""]
	if idea["body"] != "An idea" || idea["trashedAt"].(float64) == 0 {
		t.Fatalf("idea: %v", idea)
	}
	imgs, _ := idea["images"].([]any)
	if len(imgs) != 1 || imgs[0].(map[string]any)["w"].(float64) != 20 {
		t.Fatalf("images: %v", idea["images"])
	}
}
