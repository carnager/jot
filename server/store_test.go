package main

import (
	"encoding/json"
	"testing"
)

func testStore(t *testing.T) (*Store, int64) {
	t.Helper()
	s, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	if err := s.AddUser("alice", "secret123"); err != nil {
		t.Fatal(err)
	}
	u, err := s.UserByName("alice")
	if err != nil {
		t.Fatal(err)
	}
	return s, u.ID
}

func raws(items ...string) []json.RawMessage {
	out := make([]json.RawMessage, len(items))
	for i, s := range items {
		out[i] = json.RawMessage(s)
	}
	return out
}

func decode(t *testing.T, items []json.RawMessage) []map[string]any {
	t.Helper()
	out := make([]map[string]any, len(items))
	for i, raw := range items {
		if err := json.Unmarshal(raw, &out[i]); err != nil {
			t.Fatalf("item %d: %v: %s", i, err, raw)
		}
	}
	return out
}

func TestSyncLastWriteWins(t *testing.T) {
	s, uid := testStore(t)
	rev, items, changed, err := s.Sync(uid, 0, raws(`{"id":"a","title":"one","updatedAt":100,"rev":42,"future":[1]}`))
	if err != nil || !changed || rev != 1 || len(items) != 1 {
		t.Fatalf("first sync: rev=%d changed=%v items=%d err=%v", rev, changed, len(items), err)
	}
	got := decode(t, items)[0]
	if got["rev"].(float64) != 1 || got["future"] == nil {
		t.Fatalf("rev not replaced or unknown field lost: %v", got)
	}

	// A stale write loses, and the winner comes back even though the client has seen its rev.
	rev, items, changed, err = s.Sync(uid, 1, raws(`{"id":"a","title":"stale","updatedAt":50}`))
	if err != nil || changed || rev != 1 {
		t.Fatalf("stale sync: rev=%d changed=%v err=%v", rev, changed, err)
	}
	if got := decode(t, items); len(got) != 1 || got[0]["title"] != "one" {
		t.Fatalf("expected winning copy, got %v", got)
	}

	// Equal or newer timestamps win.
	rev, items, _, err = s.Sync(uid, 1, raws(`{"id":"a","title":"two","updatedAt":100}`))
	if err != nil || rev != 2 || decode(t, items)[0]["title"] != "two" {
		t.Fatalf("newer sync: rev=%d items=%s err=%v", rev, items, err)
	}

	// Incremental pull returns nothing new.
	_, items, _, err = s.Sync(uid, 2, nil)
	if err != nil || len(items) != 0 {
		t.Fatalf("incremental: %s %v", items, err)
	}
}

func TestSyncIsolatesUsers(t *testing.T) {
	s, uid := testStore(t)
	if err := s.AddUser("bob", "secret123"); err != nil {
		t.Fatal(err)
	}
	bob, _ := s.UserByName("bob")
	if _, _, _, err := s.Sync(uid, 0, raws(`{"id":"same","title":"alice","updatedAt":1}`)); err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := s.Sync(bob.ID, 0, raws(`{"id":"same","title":"bob","updatedAt":1}`)); err != nil {
		t.Fatal(err)
	}
	_, items, _, _ := s.Sync(uid, 0, nil)
	if got := decode(t, items); len(got) != 1 || got[0]["title"] != "alice" {
		t.Fatalf("alice sees %v", got)
	}
}

func TestSyncRejectsBadItems(t *testing.T) {
	s, uid := testStore(t)
	for _, bad := range []string{`{"title":"no id"}`, `[1,2]`, `{"id":""}`} {
		if _, _, _, err := s.Sync(uid, 0, raws(bad)); err == nil {
			t.Errorf("accepted %s", bad)
		}
	}
}

func TestTokens(t *testing.T) {
	s, uid := testStore(t)
	tok, err := s.NewToken(uid, "test")
	if err != nil {
		t.Fatal(err)
	}
	if u, err := s.UserForToken(tok); err != nil || u.Name != "alice" {
		t.Fatalf("token lookup: %v %v", u, err)
	}
	if err := s.SetPassword("alice", "another1"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.UserForToken(tok); err != ErrNotFound {
		t.Fatalf("password change should revoke tokens, got %v", err)
	}
	if _, err := s.CheckPassword("alice", "secret123"); err == nil {
		t.Fatal("old password still works")
	}
	if _, err := s.CheckPassword("alice", "another1"); err != nil {
		t.Fatal(err)
	}
}
