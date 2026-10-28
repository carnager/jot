package main

import (
	"bytes"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
	"unicode/utf8"
)

const reminderSchema = `
CREATE TABLE IF NOT EXISTS settings (
	user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
	data    TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS reminders_sent (
	user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	item_id TEXT NOT NULL,
	fire_at INTEGER NOT NULL,
	PRIMARY KEY (user_id, item_id, fire_at)
);`

// ---- settings (SPEC §2c) ----

// Settings keeps the raw object so client-specific keys survive; the server
// reads only what it needs.
type Settings map[string]json.RawMessage

type ntfyConfig struct {
	URL   string `json:"url"`
	Token string `json:"token"`
}

func (s Settings) str(key string) string {
	var v string
	json.Unmarshal(s[key], &v)
	return v
}

func (s Settings) ntfy() ntfyConfig {
	var n ntfyConfig
	json.Unmarshal(s["ntfy"], &n)
	return n
}

func (s Settings) location() *time.Location {
	if loc, err := time.LoadLocation(s.str("timezone")); err == nil && s.str("timezone") != "" {
		return loc
	}
	if loc, err := time.LoadLocation(localZoneName()); err == nil {
		return loc
	}
	return time.Local
}

// allDayMinutes is the all-day reminder time in minutes after midnight (default 09:00).
func (s Settings) allDayMinutes() int {
	var h, m int
	if _, err := fmt.Sscanf(s.str("allDayTime"), "%d:%d", &h, &m); err == nil && h >= 0 && h < 24 && m >= 0 && m < 60 {
		return h*60 + m
	}
	return 9 * 60
}

func (s *Store) Settings(userID int64) (Settings, error) {
	var data string
	err := s.db.QueryRow(`SELECT data FROM settings WHERE user_id = ?`, userID).Scan(&data)
	set := Settings{}
	if errors.Is(err, sql.ErrNoRows) {
		err = nil
	} else if err == nil {
		err = json.Unmarshal([]byte(data), &set)
	}
	if err != nil {
		return nil, err
	}
	if set.str("timezone") == "" {
		set["timezone"] = mustJSON(localZoneName())
	}
	if set.str("allDayTime") == "" {
		set["allDayTime"] = mustJSON("09:00")
	}
	if _, ok := set["ntfy"]; !ok {
		set["ntfy"] = mustJSON(ntfyConfig{})
	}
	return set, nil
}

func (s *Store) PutSettings(userID int64, set Settings) error {
	data, err := json.Marshal(set)
	if err != nil {
		return err
	}
	_, err = s.db.Exec(`INSERT INTO settings (user_id, data) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET data = excluded.data`, userID, string(data))
	return err
}

// localZoneName is the server's IANA zone name ("Local" means nothing to clients).
func localZoneName() string {
	if tz := strings.TrimPrefix(os.Getenv("TZ"), ":"); tz != "" {
		if _, err := time.LoadLocation(tz); err == nil {
			return tz
		}
	}
	if target, err := os.Readlink("/etc/localtime"); err == nil {
		if i := strings.Index(target, "zoneinfo/"); i >= 0 {
			return target[i+len("zoneinfo/"):]
		}
	}
	return "UTC"
}

func randomToken() string {
	b := make([]byte, 24)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func publicURL(r *http.Request) string {
	if o := r.Header.Get("Origin"); strings.HasPrefix(o, "http") {
		return strings.TrimRight(o, "/")
	}
	scheme := "http"
	if isSecure(r) {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

func (s *Server) handleGetSettings(w http.ResponseWriter, r *http.Request) {
	set, err := s.store.Settings(userFrom(r).ID)
	if err != nil {
		log.Printf("settings: %v", err)
		writeErr(w, http.StatusInternalServerError, "could not load settings")
		return
	}
	writeJSON(w, http.StatusOK, set)
}

func (s *Server) handlePutSettings(w http.ResponseWriter, r *http.Request) {
	uid := userFrom(r).ID
	var in Settings
	if !readJSON(w, r, &in) {
		return
	}
	old, err := s.store.Settings(uid)
	if err != nil {
		log.Printf("settings: %v", err)
		writeErr(w, http.StatusInternalServerError, "could not load settings")
		return
	}
	if tz := in.str("timezone"); tz != "" {
		if _, err := time.LoadLocation(tz); err != nil {
			writeErr(w, http.StatusBadRequest, "unknown timezone "+tz)
			return
		}
	}
	if n := in.ntfy(); n.URL != "" {
		if u, err := url.Parse(n.URL); err != nil || (u.Scheme != "https" && u.Scheme != "http") || strings.Trim(u.Path, "/") == "" {
			writeErr(w, http.StatusBadRequest, "ntfy url must look like https://ntfy.example.com/topic")
			return
		}
	}
	// Server-owned keys can't be set by clients.
	for _, k := range []string{"actionToken", "publicUrl", "ntfyEnabledAt"} {
		delete(in, k)
		if v, ok := old[k]; ok {
			in[k] = v
		}
	}
	in["publicUrl"] = mustJSON(publicURL(r))
	if in.str("actionToken") == "" {
		in["actionToken"] = mustJSON(randomToken())
	}
	// Turning ntfy on must not replay the last 12 hours of reminders.
	if in.ntfy().URL != "" && in.ntfy().URL != old.ntfy().URL {
		in["ntfyEnabledAt"] = mustJSON(time.Now().UnixMilli())
	}
	if err := s.store.PutSettings(uid, in); err != nil {
		log.Printf("settings: %v", err)
		writeErr(w, http.StatusInternalServerError, "could not save settings")
		return
	}
	writeJSON(w, http.StatusOK, in)
}

func (s *Server) handleNtfyTest(w http.ResponseWriter, r *http.Request) {
	set, err := s.store.Settings(userFrom(r).ID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "could not load settings")
		return
	}
	if set.ntfy().URL == "" {
		writeErr(w, http.StatusBadRequest, "no ntfy topic configured")
		return
	}
	msg := ntfyMessage{Title: "Jot reminders work", Message: "This is what a reminder will look like.", Tags: []string{"alarm_clock"}, Priority: 3}
	if u := set.str("publicUrl"); u != "" {
		msg.Click = u + "/"
	}
	if err := sendNtfy(set.ntfy(), msg); err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---- fire times (SPEC §5) ----

type reminderItem struct {
	ID     string `json:"id"`
	Title  string `json:"title"`
	Body   string `json:"body"`
	Checks []struct {
		Text    string `json:"text"`
		Done    bool   `json:"done"`
		Deleted bool   `json:"deleted"`
	} `json:"checks"`
	Due          string `json:"due"`
	Remind       *bool  `json:"remind"`
	Alerts       []int  `json:"alerts"`
	SnoozedUntil string `json:"snoozedUntil"`
	Done         bool   `json:"done"`
	Archived     bool   `json:"archived"`
	TrashedAt    int64  `json:"trashedAt"`
	Deleted      bool   `json:"deleted"`
}

// fireTimes returns when an item's notifications fire, with how many minutes
// before the base time each one is (0 = on time, -1 = snoozed).
func fireTimes(it reminderItem, loc *time.Location, allDayMinutes int) (times []time.Time, before []int) {
	if (it.Remind != nil && !*it.Remind) || it.Done || it.Archived || it.TrashedAt > 0 || it.Deleted || it.Due == "" {
		return nil, nil
	}
	at := func(due string, defMinutes int) (time.Time, bool) {
		day, minutes, ok := parseDue(due)
		if !ok {
			return time.Time{}, false
		}
		if minutes < 0 {
			minutes = defMinutes
		}
		return time.Date(day.Year(), day.Month(), day.Day(), minutes/60, minutes%60, 0, 0, loc), true
	}
	if it.SnoozedUntil != "" {
		if t, ok := at(it.SnoozedUntil, allDayMinutes); ok {
			return []time.Time{t}, []int{-1}
		}
	}
	base, ok := at(it.Due, allDayMinutes)
	if !ok {
		return nil, nil
	}
	alerts := it.Alerts
	if len(alerts) == 0 {
		alerts = []int{0}
	}
	for _, m := range alerts {
		times = append(times, base.Add(-time.Duration(m)*time.Minute))
		before = append(before, m)
	}
	return times, before
}

func describeAlert(due string, before int, loc *time.Location, now time.Time) string {
	day, minutes, ok := parseDue(due)
	if !ok {
		return ""
	}
	today := civilDate(now.In(loc))
	var when string
	switch diff := int(day.Sub(today).Hours() / 24); {
	case diff == 0:
		when = "Today"
	case diff == 1:
		when = "Tomorrow"
	case diff == -1:
		when = "Yesterday"
	case diff > 1 && diff < 7:
		when = day.Format("Monday")
	default:
		when = day.Format("Mon 2 Jan")
	}
	if minutes >= 0 {
		when += fmt.Sprintf(" %02d:%02d", minutes/60, minutes%60)
	}
	switch {
	case before > 0 && before < 60:
		return fmt.Sprintf("in %d min · %s", before, when)
	case before >= 60 && before < 24*60 && before%60 == 0:
		return fmt.Sprintf("in %d h · %s", before/60, when)
	}
	return when
}

func preview(it reminderItem) string {
	text := strings.TrimSpace(it.Body)
	if text == "" {
		var open []string
		for _, c := range it.Checks {
			if !c.Done && !c.Deleted && strings.TrimSpace(c.Text) != "" {
				open = append(open, c.Text)
			}
		}
		text = strings.Join(open, ", ")
	}
	if utf8.RuneCountInString(text) > 140 {
		text = string([]rune(text)[:140]) + "…"
	}
	return text
}

// ---- ntfy ----

type ntfyAction struct {
	Action string `json:"action"`
	Label  string `json:"label"`
	URL    string `json:"url"`
	Method string `json:"method,omitempty"`
	Clear  bool   `json:"clear,omitempty"`
}

type ntfyMessage struct {
	Topic    string       `json:"topic"`
	Title    string       `json:"title"`
	Message  string       `json:"message"`
	Tags     []string     `json:"tags,omitempty"`
	Priority int          `json:"priority,omitempty"`
	Click    string       `json:"click,omitempty"`
	Actions  []ntfyAction `json:"actions,omitempty"`
}

var ntfyClient = &http.Client{Timeout: 15 * time.Second}

// sendNtfy publishes via ntfy's JSON API (POST to the server root with the
// topic in the body), which handles non-ASCII titles cleanly.
func sendNtfy(cfg ntfyConfig, msg ntfyMessage) error {
	u, err := url.Parse(cfg.URL)
	if err != nil {
		return err
	}
	msg.Topic = strings.Trim(u.Path, "/")
	root := u.Scheme + "://" + u.Host
	body, _ := json.Marshal(msg)
	req, err := http.NewRequest(http.MethodPost, root, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if cfg.Token != "" {
		req.Header.Set("Authorization", "Bearer "+cfg.Token)
	}
	resp, err := ntfyClient.Do(req)
	if err != nil {
		return fmt.Errorf("ntfy: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("ntfy answered %s", resp.Status)
	}
	return nil
}

// ---- the reminder loop ----

func (s *Server) reminderLoop() {
	tick := time.NewTicker(30 * time.Second)
	defer tick.Stop()
	for {
		if err := s.sendDueReminders(time.Now()); err != nil {
			log.Printf("reminders: %v", err)
		}
		<-tick.C
	}
}

func (s *Server) sendDueReminders(now time.Time) error {
	rows, err := s.store.db.Query(`SELECT user_id, data FROM settings WHERE data LIKE '%"ntfy"%'`)
	if err != nil {
		return err
	}
	type userSettings struct {
		id  int64
		set Settings
	}
	var users []userSettings
	for rows.Next() {
		var u userSettings
		var data string
		if err := rows.Scan(&u.id, &data); err != nil {
			rows.Close()
			return err
		}
		if json.Unmarshal([]byte(data), &u.set) == nil && u.set.ntfy().URL != "" {
			users = append(users, u)
		}
	}
	rows.Close()

	for _, u := range users {
		if err := s.sendUserReminders(u.id, u.set, now); err != nil {
			log.Printf("reminders for user %d: %v", u.id, err)
		}
	}
	_, err = s.store.db.Exec(`DELETE FROM reminders_sent WHERE fire_at < ?`, now.Add(-7*24*time.Hour).UnixMilli())
	return err
}

func (s *Server) sendUserReminders(userID int64, set Settings, now time.Time) error {
	loc := set.location()
	allDay := set.allDayMinutes()
	var enabledAt int64
	json.Unmarshal(set["ntfyEnabledAt"], &enabledAt)
	from := now.Add(-12 * time.Hour)
	if e := time.UnixMilli(enabledAt); e.After(from) {
		from = e
	}

	rows, err := s.store.db.Query(`SELECT data FROM items WHERE user_id = ? AND data LIKE '%"due":"2%'`, userID)
	if err != nil {
		return err
	}
	type pending struct {
		it     reminderItem
		at     time.Time
		before int
	}
	var due []pending
	for rows.Next() {
		var data string
		if err := rows.Scan(&data); err != nil {
			rows.Close()
			return err
		}
		var it reminderItem
		if json.Unmarshal([]byte(data), &it) != nil {
			continue
		}
		times, before := fireTimes(it, loc, allDay)
		for i, t := range times {
			if t.After(from) && !t.After(now) {
				due = append(due, pending{it, t, before[i]})
			}
		}
	}
	rows.Close()

	base := set.str("publicUrl")
	token := set.str("actionToken")
	for _, p := range due {
		res, err := s.store.db.Exec(`INSERT OR IGNORE INTO reminders_sent (user_id, item_id, fire_at) VALUES (?, ?, ?)`, userID, p.it.ID, p.at.UnixMilli())
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			continue // already sent
		}
		title := strings.TrimSpace(p.it.Title)
		if title == "" {
			title = "Reminder"
		}
		when := p.it.Due
		if p.before < 0 {
			when = p.it.SnoozedUntil
		}
		msg := ntfyMessage{
			Title:    title,
			Message:  strings.TrimSpace(describeAlert(when, max(p.before, 0), loc, now) + "\n" + preview(p.it)),
			Tags:     []string{"alarm_clock"},
			Priority: 4,
		}
		if base != "" {
			msg.Click = base + "/#/item/" + url.PathEscape(p.it.ID)
			if token != "" {
				action := base + "/api/remind/" + token + "/" + url.PathEscape(p.it.ID) + "/"
				msg.Actions = []ntfyAction{
					{Action: "http", Label: "Done", URL: action + "done", Method: "POST", Clear: true},
					{Action: "http", Label: "Snooze 1 h", URL: action + "snooze", Method: "POST", Clear: true},
				}
			}
		}
		if err := sendNtfy(set.ntfy(), msg); err != nil {
			// Forget it so the next tick retries (until it's older than 12 h).
			s.store.db.Exec(`DELETE FROM reminders_sent WHERE user_id = ? AND item_id = ? AND fire_at = ?`, userID, p.it.ID, p.at.UnixMilli())
			return err
		}
	}
	return nil
}

// ---- actions from notifications ----

// handleRemindAction completes or snoozes an item from an ntfy action button.
// The action token is the only credential, and it can do nothing but this.
func (s *Server) handleRemindAction(w http.ResponseWriter, r *http.Request) {
	token, itemID, op := r.PathValue("token"), r.PathValue("item"), r.PathValue("op")
	if op != "done" && op != "snooze" {
		writeErr(w, http.StatusNotFound, "unknown action")
		return
	}
	var userID int64
	var data string
	err := s.store.db.QueryRow(`SELECT user_id FROM settings WHERE json_extract(data, '$.actionToken') = ? AND ? != ''`, token, token).Scan(&userID)
	if err == nil {
		err = s.store.db.QueryRow(`SELECT data FROM items WHERE user_id = ? AND id = ?`, userID, itemID).Scan(&data)
	}
	if err != nil {
		time.Sleep(300 * time.Millisecond)
		writeErr(w, http.StatusNotFound, "not found")
		return
	}
	set, err := s.store.Settings(userID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "could not load settings")
		return
	}
	var item map[string]json.RawMessage
	if err := json.Unmarshal([]byte(data), &item); err != nil {
		writeErr(w, http.StatusInternalServerError, "broken item")
		return
	}
	now := time.Now()
	local := now.In(set.location())
	localNow := time.Date(local.Year(), local.Month(), local.Day(), local.Hour(), local.Minute(), 0, 0, time.UTC)
	nowMs := now.UnixMilli()
	touched := []string{"schedule"}

	switch op {
	case "snooze":
		item["snoozedUntil"] = mustJSON(localNow.Add(time.Hour).Format("2006-01-02T15:04"))
	case "done":
		var due string
		var rep *Repeat
		json.Unmarshal(item["due"], &due)
		json.Unmarshal(item["repeat"], &rep)
		next, done := "", true
		if rep != nil && due != "" {
			next, done = completeRepeat(due, *rep, localNow)
		}
		item["snoozedUntil"] = mustJSON("")
		item["doneAt"] = mustJSON(nowMs)
		if done {
			item["done"] = mustJSON(true)
		} else {
			item["due"] = mustJSON(next)
			// A repeating checklist starts over.
			var checks []map[string]json.RawMessage
			if json.Unmarshal(item["checks"], &checks) == nil && len(checks) > 0 {
				for _, c := range checks {
					if !isTrue(c["deleted"]) {
						c["done"] = mustJSON(false)
						c["u"] = mustJSON(nowMs)
					}
				}
				item["checks"] = mustJSON(checks)
			}
		}
	}
	var fm map[string]int64
	json.Unmarshal(item["fm"], &fm)
	if fm == nil {
		fm = map[string]int64{}
	}
	for _, g := range touched {
		fm[g] = nowMs
	}
	item["fm"] = mustJSON(fm)
	item["updatedAt"] = mustJSON(nowMs)
	raw, _ := json.Marshal(item)
	rev, _, changed, err := s.store.Sync(userID, 1<<62, []json.RawMessage{raw})
	if err != nil {
		log.Printf("remind action: %v", err)
		writeErr(w, http.StatusInternalServerError, "could not update item")
		return
	}
	if changed {
		s.hub.Publish(userID, rev)
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}
