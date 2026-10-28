package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestFireTimes(t *testing.T) {
	berlin, _ := time.LoadLocation("Europe/Berlin")
	f := false
	cases := []struct {
		item reminderItem
		want []string
	}{
		{reminderItem{Due: "2026-10-29T14:00"}, []string{"2026-10-29 14:00"}},
		{reminderItem{Due: "2026-10-29"}, []string{"2026-10-29 09:00"}},
		{reminderItem{Due: "2026-10-29T14:00", Alerts: []int{0, 30, 1440}}, []string{"2026-10-29 14:00", "2026-10-29 13:30", "2026-10-28 14:00"}},
		{reminderItem{Due: "2026-10-29T14:00", SnoozedUntil: "2026-10-29T15:10", Alerts: []int{30}}, []string{"2026-10-29 15:10"}},
		{reminderItem{Due: "2026-10-29T14:00", Done: true}, nil},
		{reminderItem{Due: "2026-10-29T14:00", Remind: &f}, nil},
		{reminderItem{Due: "2026-10-29T14:00", TrashedAt: 1}, nil},
		{reminderItem{}, nil},
	}
	for i, c := range cases {
		times, _ := fireTimes(c.item, berlin, 9*60)
		var got []string
		for _, tm := range times {
			if tm.Location() != berlin {
				t.Errorf("case %d: wrong zone", i)
			}
			got = append(got, tm.Format("2006-01-02 15:04"))
		}
		if strings.Join(got, ",") != strings.Join(c.want, ",") {
			t.Errorf("case %d: got %v, want %v", i, got, c.want)
		}
	}
}

func TestNtfyRemindersAndActions(t *testing.T) {
	var mu sync.Mutex
	var sent []ntfyMessage
	ntfy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var m ntfyMessage
		json.NewDecoder(r.Body).Decode(&m)
		if r.Header.Get("Authorization") != "Bearer tk" {
			t.Errorf("missing token")
		}
		mu.Lock()
		sent = append(sent, m)
		mu.Unlock()
	}))
	defer ntfy.Close()

	s, uid := testStore(t)
	srv := &Server{store: s, hub: NewHub()}
	h := srv.routes(http.NotFoundHandler())
	tok, _ := s.NewToken(uid, "test")

	put := httptest.NewRequest(http.MethodPut, "/api/settings", strings.NewReader(
		`{"timezone":"Europe/Berlin","allDayTime":"08:30","ntfy":{"url":"`+ntfy.URL+`/jot-test","token":"tk"},"actionToken":"evil"}`))
	put.Header.Set("Authorization", "Bearer "+tok)
	put.Header.Set("Origin", "https://jot.example.com")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, put)
	if rec.Code != 200 {
		t.Fatalf("put settings: %d %s", rec.Code, rec.Body)
	}
	set, _ := s.Settings(uid)
	action := set.str("actionToken")
	if action == "" || action == "evil" || set.str("publicUrl") != "https://jot.example.com" {
		t.Fatalf("server-owned keys: %v", set)
	}
	// Pretend ntfy was enabled long ago so the 12 h window applies.
	set["ntfyEnabledAt"] = mustJSON(int64(0))
	s.PutSettings(uid, set)

	berlin, _ := time.LoadLocation("Europe/Berlin")
	now := time.Date(2026, 10, 28, 14, 0, 30, 0, berlin)
	s.Sync(uid, 0, raws(
		`{"id":"gym","title":"Gym","due":"2026-10-28T14:00","repeat":{"freq":"day","interval":1},"checks":[{"id":"c","text":"shoes","done":true}],"updatedAt":1}`,
		`{"id":"early","title":"Call","due":"2026-10-28T14:30","alerts":[30],"updatedAt":1}`,
		`{"id":"later","title":"Later","due":"2026-10-28T18:00","updatedAt":1}`,
		`{"id":"old","title":"Old","due":"2026-10-27T09:00","updatedAt":1}`,
	))
	for range 2 { // the second pass must not resend
		if err := srv.sendDueReminders(now); err != nil {
			t.Fatal(err)
		}
	}
	if len(sent) != 2 {
		t.Fatalf("sent %d messages: %+v", len(sent), sent)
	}
	titles := sent[0].Title + "," + sent[1].Title
	if !strings.Contains(titles, "Gym") || !strings.Contains(titles, "Call") {
		t.Fatalf("titles %s", titles)
	}
	for _, m := range sent {
		if m.Topic != "jot-test" || len(m.Actions) != 2 || !strings.HasPrefix(m.Click, "https://jot.example.com/#/item/") {
			t.Fatalf("message %+v", m)
		}
		if m.Title == "Call" && !strings.HasPrefix(m.Message, "in 30 min · Today 14:30") {
			t.Errorf("early alert text %q", m.Message)
		}
	}

	// "Done" from the notification advances the repeating item and resets its checklist.
	var doneURL string
	for _, m := range sent {
		if m.Title == "Gym" {
			doneURL = strings.TrimPrefix(m.Actions[0].URL, "https://jot.example.com")
		}
	}
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, doneURL, nil))
	if rec.Code != 200 {
		body, _ := io.ReadAll(rec.Body)
		t.Fatalf("done action: %d %s", rec.Code, body)
	}
	_, items, _, _ := s.Sync(uid, 0, nil)
	for _, it := range decode(t, items) {
		if it["id"] != "gym" {
			continue
		}
		if it["done"] == true || !strings.HasPrefix(it["due"].(string), "2026-") || it["due"] == "2026-10-28T14:00" {
			t.Fatalf("gym after done: %v", it)
		}
		if c := it["checks"].([]any)[0].(map[string]any); c["done"] != false {
			t.Fatalf("checklist not reset: %v", c)
		}
	}

	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/remind/wrong/gym/done", nil))
	if rec.Code != 404 {
		t.Fatalf("wrong token: %d", rec.Code)
	}
}
