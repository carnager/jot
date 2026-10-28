package main

import (
	"encoding/json"
	"testing"
)

func obj(t *testing.T, s string) map[string]json.RawMessage {
	t.Helper()
	var m map[string]json.RawMessage
	if err := json.Unmarshal([]byte(s), &m); err != nil {
		t.Fatalf("%v: %s", err, s)
	}
	return m
}

func field[T any](t *testing.T, m map[string]json.RawMessage, k string) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(m[k], &v); err != nil {
		t.Fatalf("field %s: %v (%s)", k, err, m[k])
	}
	return v
}

type testCheck struct {
	ID      string `json:"id"`
	Text    string `json:"text"`
	Done    bool   `json:"done"`
	Deleted bool   `json:"deleted"`
}

func TestMergeKeepsConcurrentFieldEdits(t *testing.T) {
	// Phone renamed the note at t=200; web changed the color at t=300 from an older copy.
	stored := obj(t, `{"id":"a","title":"New title","color":"","updatedAt":200,"fm":{"title":200,"color":100}}`)
	in := obj(t, `{"id":"a","title":"Old title","color":"red","updatedAt":300,"fm":{"title":100,"color":300}}`)
	m := mergeItems(stored, in)
	if got := field[string](t, m, "title"); got != "New title" {
		t.Errorf("title = %q", got)
	}
	if got := field[string](t, m, "color"); got != "red" {
		t.Errorf("color = %q", got)
	}
	if got := field[int64](t, m, "updatedAt"); got != 300 {
		t.Errorf("updatedAt = %d", got)
	}
	if fm := field[map[string]int64](t, m, "fm"); fm["title"] != 200 || fm["color"] != 300 {
		t.Errorf("fm = %v", fm)
	}
}

func TestMergeChecksPerItem(t *testing.T) {
	// Phone (stored) ticked milk and deleted eggs; web (incoming, newer order) added bread and renamed butter.
	stored := obj(t, `{"id":"l","updatedAt":200,"fm":{"order":200},"checks":[
		{"id":"milk","text":"Milk","done":true,"u":200},
		{"id":"eggs","deleted":true,"u":200},
		{"id":"butter","text":"Butter","done":false,"u":50}]}`)
	in := obj(t, `{"id":"l","updatedAt":300,"fm":{"order":300},"checks":[
		{"id":"bread","text":"Bread","done":false,"u":300},
		{"id":"milk","text":"Milk","done":false,"u":50},
		{"id":"eggs","text":"Eggs","done":false,"u":50},
		{"id":"butter","text":"Kerrygold","done":false,"u":300}]}`)
	checks := field[[]testCheck](t, mergeItems(stored, in), "checks")
	want := []testCheck{
		{ID: "bread", Text: "Bread"},
		{ID: "milk", Text: "Milk", Done: true},
		{ID: "eggs", Deleted: true},
		{ID: "butter", Text: "Kerrygold"},
	}
	if len(checks) != len(want) {
		t.Fatalf("checks = %+v", checks)
	}
	for i := range want {
		if checks[i] != want[i] {
			t.Errorf("check %d = %+v, want %+v", i, checks[i], want[i])
		}
	}
}

func TestMergeLegacyClientIsLastWriteWins(t *testing.T) {
	stored := obj(t, `{"id":"a","title":"server","checks":[{"id":"x","text":"keep"}],"extra":1,"updatedAt":200}`)
	in := obj(t, `{"id":"a","title":"client","checks":[],"updatedAt":300}`)
	m := mergeItems(stored, in)
	if field[string](t, m, "title") != "client" || len(field[[]testCheck](t, m, "checks")) != 0 {
		t.Fatalf("newer legacy write should win wholesale: %s", mustJSON(m))
	}
	if _, ok := m["extra"]; ok {
		t.Error("unknown fields should come from the newer side")
	}
	if _, ok := m["fm"]; ok {
		t.Error("fm should not appear when neither side tracks it")
	}
	old := obj(t, `{"id":"a","title":"stale","updatedAt":100}`)
	if field[string](t, mergeItems(stored, old), "title") != "server" {
		t.Error("stale legacy write should lose")
	}
}

func TestMergeDeleteIsFinal(t *testing.T) {
	stored := obj(t, `{"id":"a","deleted":true,"updatedAt":100,"fm":{}}`)
	in := obj(t, `{"id":"a","title":"edited later","deleted":false,"updatedAt":500,"fm":{"title":500}}`)
	if !isTrue(mergeItems(stored, in)["deleted"]) {
		t.Fatal("deleted item came back")
	}
}

func TestSyncMergesAndConverges(t *testing.T) {
	s, uid := testStore(t)
	s.Sync(uid, 0, raws(`{"id":"a","title":"T","color":"","updatedAt":100,"fm":{"title":100,"color":100}}`))
	// Device A renames, device B (unaware) recolors.
	s.Sync(uid, 1, raws(`{"id":"a","title":"Renamed","color":"","updatedAt":200,"fm":{"title":200,"color":100}}`))
	rev, items, changed, err := s.Sync(uid, 1, raws(`{"id":"a","title":"T","color":"blue","updatedAt":150,"fm":{"title":100,"color":150}}`))
	if err != nil || !changed || rev != 3 {
		t.Fatalf("rev=%d changed=%v err=%v", rev, changed, err)
	}
	got := decode(t, items)
	if len(got) != 1 || got[0]["title"] != "Renamed" || got[0]["color"] != "blue" {
		t.Fatalf("merged = %v", got)
	}
	// Re-sending what's already merged changes nothing but still returns the item.
	_, items, changed, _ = s.Sync(uid, 3, raws(`{"id":"a","title":"T","color":"blue","updatedAt":150,"fm":{"title":100,"color":150}}`))
	if changed || len(items) != 1 {
		t.Fatalf("expected no-op returning stored copy, changed=%v items=%d", changed, len(items))
	}
}

func TestMergeLegacyEditKeepingStaleFm(t *testing.T) {
	// The web stamped the title at 100; an older Android build later renamed the
	// note at 500 but sent the stale fm back unchanged.
	stored := obj(t, `{"id":"a","title":"web","color":"red","updatedAt":100,"fm":{"title":100,"color":100}}`)
	in := obj(t, `{"id":"a","title":"phone","color":"red","updatedAt":500,"fm":{"title":100,"color":100}}`)
	m := mergeItems(stored, in)
	if got := field[string](t, m, "title"); got != "phone" {
		t.Fatalf("legacy edit lost: title = %q", got)
	}
	// A later tracked edit of another group still merges against it correctly.
	next := obj(t, `{"id":"a","title":"web","color":"blue","updatedAt":600,"fm":{"title":100,"color":600}}`)
	m2 := mergeItems(m, next)
	if field[string](t, m2, "title") != "phone" || field[string](t, m2, "color") != "blue" {
		t.Fatalf("after tracked edit: %s", mustJSON(m2))
	}
}

func TestMergeCheckTickCountsAsTracked(t *testing.T) {
	// Web ticked "milk" at 300 (only its u changes); the phone (tracked) renamed the list at 250.
	stored := obj(t, `{"id":"l","title":"Shopping","updatedAt":250,"fm":{"title":250,"order":100},"checks":[{"id":"milk","text":"Milk","done":false,"u":100}]}`)
	in := obj(t, `{"id":"l","title":"Groceries old","updatedAt":300,"fm":{"title":50,"order":100},"checks":[{"id":"milk","text":"Milk","done":true,"u":300}]}`)
	m := mergeItems(stored, in)
	if field[string](t, m, "title") != "Shopping" {
		t.Fatalf("title should stay: %s", mustJSON(m))
	}
	if c := field[[]testCheck](t, m, "checks"); len(c) != 1 || !c[0].Done {
		t.Fatalf("tick lost: %s", mustJSON(m))
	}
}
