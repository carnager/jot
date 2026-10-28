package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	cookieName   = "jot_session"
	maxBodyBytes = 20 << 20
	maxItemBytes = 1 << 20
)

type ctxKey struct{}

type Server struct {
	store *Store
	hub   *Hub
}

func (s *Server) routes(web http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/login", s.handleLogin)
	mux.HandleFunc("POST /api/logout", s.auth(s.handleLogout))
	mux.HandleFunc("GET /api/me", s.auth(s.handleMe))
	mux.HandleFunc("POST /api/sync", s.auth(s.handleSync))
	mux.HandleFunc("GET /api/events", s.auth(s.handleEvents))
	mux.HandleFunc("GET /api/export", s.auth(s.handleExport))
	mux.HandleFunc("PUT /api/attachments/{id}", s.auth(s.handlePutAttachment))
	mux.HandleFunc("GET /api/attachments/{id}", s.auth(s.handleGetAttachment))
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		writeErr(w, http.StatusNotFound, "no such endpoint")
	})
	mux.Handle("/", web)
	return logRequests(mux)
}

// ---- helpers ----

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeErr(w, http.StatusRequestEntityTooLarge, "request too large")
		} else {
			writeErr(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		}
		return false
	}
	return true
}

func isSecure(r *http.Request) bool {
	return r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https")
}

func userFrom(r *http.Request) User { return r.Context().Value(ctxKey{}).(User) }

func tokenFrom(r *http.Request) (tok string, viaCookie bool) {
	if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
		return strings.TrimSpace(h[len("Bearer "):]), false
	}
	if c, err := r.Cookie(cookieName); err == nil {
		return c.Value, true
	}
	return "", false
}

// auth resolves the bearer token or session cookie. Cookie-authenticated writes
// must be JSON, which a cross-site form cannot send without a CORS preflight.
func (s *Server) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok, viaCookie := tokenFrom(r)
		if tok == "" {
			writeErr(w, http.StatusUnauthorized, "not logged in")
			return
		}
		if viaCookie && r.Method == http.MethodPost && !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
			writeErr(w, http.StatusUnsupportedMediaType, "expected application/json")
			return
		}
		u, err := s.store.UserForToken(tok)
		if err != nil {
			if !errors.Is(err, ErrNotFound) {
				log.Printf("auth: %v", err)
			}
			writeErr(w, http.StatusUnauthorized, "not logged in")
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, u)))
	}
}

// ---- handlers ----

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
		Device   string `json:"device"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	u, err := s.store.CheckPassword(strings.TrimSpace(req.Username), req.Password)
	if err != nil {
		if !errors.Is(err, ErrNotFound) {
			log.Printf("login: %v", err)
		}
		time.Sleep(400 * time.Millisecond) // slows down guessing
		writeErr(w, http.StatusUnauthorized, "wrong username or password")
		return
	}
	device := req.Device
	if device == "" {
		device = r.UserAgent()
	}
	if len(device) > 200 {
		device = device[:200]
	}
	tok, err := s.store.NewToken(u.ID, device)
	if err != nil {
		log.Printf("login: %v", err)
		writeErr(w, http.StatusInternalServerError, "could not create session")
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name: cookieName, Value: tok, Path: "/", HttpOnly: true, Secure: isSecure(r),
		SameSite: http.SameSiteLaxMode, MaxAge: 400 * 24 * 3600,
	})
	writeJSON(w, http.StatusOK, map[string]string{"token": tok, "username": u.Name})
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	tok, _ := tokenFrom(r)
	if err := s.store.DeleteToken(tok); err != nil {
		log.Printf("logout: %v", err)
	}
	http.SetCookie(w, &http.Cookie{Name: cookieName, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, Secure: isSecure(r)})
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"username": userFrom(r).Name})
}

func (s *Server) handleSync(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Since   int64             `json:"since"`
		Changes []json.RawMessage `json:"changes"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	for _, c := range req.Changes {
		if len(c) > maxItemBytes {
			writeErr(w, http.StatusRequestEntityTooLarge, "item larger than 1 MB")
			return
		}
	}
	u := userFrom(r)
	rev, items, changed, err := s.store.Sync(u.ID, req.Since, req.Changes)
	if err != nil {
		if errors.Is(err, errBadItem) {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		log.Printf("sync: %v", err)
		writeErr(w, http.StatusInternalServerError, "sync failed")
		return
	}
	if changed {
		s.hub.Publish(u.ID, rev)
	}
	writeJSON(w, http.StatusOK, map[string]any{"rev": rev, "items": items})
}

func (s *Server) handleExport(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r)
	_, items, _, err := s.store.Sync(u.ID, 0, nil)
	if err != nil {
		log.Printf("export: %v", err)
		writeErr(w, http.StatusInternalServerError, "export failed")
		return
	}
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="jot-%s-%s.json"`, u.Name, time.Now().Format("2006-01-02")))
	writeJSON(w, http.StatusOK, map[string]any{"version": 1, "exported": time.Now().UTC(), "items": items})
}

func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeErr(w, http.StatusInternalServerError, "streaming unsupported")
		return
	}
	u := userFrom(r)
	sub := s.hub.Subscribe(u.ID)
	defer s.hub.Unsubscribe(u.ID, sub)

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no") // disable nginx buffering
	rc := http.NewResponseController(w)

	send := func(format string, args ...any) bool {
		rc.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if _, err := fmt.Fprintf(w, format, args...); err != nil {
			return false
		}
		flusher.Flush()
		return true
	}

	// Tell the client where the server is right away, so a reconnect after a
	// network blip catches up without waiting for the next change.
	rev, err := s.store.UserRev(u.ID)
	if err != nil {
		log.Printf("events: %v", err)
	}
	if !send("retry: 3000\nevent: rev\ndata: %d\n\n", rev) {
		return
	}
	ping := time.NewTicker(25 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ping.C:
			if !send(": ping\n\n") {
				return
			}
		case <-sub.signal:
			if !send("event: rev\ndata: %d\n\n", sub.latest()) {
				return
			}
			// Rate limit: at most 4 events per second; bursts coalesce.
			select {
			case <-time.After(250 * time.Millisecond):
			case <-r.Context().Done():
				return
			}
		}
	}
}

// ---- SSE hub ----

type subscriber struct {
	signal chan struct{}
	mu     sync.Mutex
	rev    int64
}

func (s *subscriber) latest() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.rev
}

type Hub struct {
	mu   sync.Mutex
	subs map[int64]map[*subscriber]struct{}
}

func NewHub() *Hub { return &Hub{subs: map[int64]map[*subscriber]struct{}{}} }

func (h *Hub) Subscribe(userID int64) *subscriber {
	sub := &subscriber{signal: make(chan struct{}, 1)}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.subs[userID] == nil {
		h.subs[userID] = map[*subscriber]struct{}{}
	}
	h.subs[userID][sub] = struct{}{}
	return sub
}

func (h *Hub) Unsubscribe(userID int64, sub *subscriber) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.subs[userID], sub)
	if len(h.subs[userID]) == 0 {
		delete(h.subs, userID)
	}
}

func (h *Hub) Publish(userID, rev int64) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for sub := range h.subs[userID] {
		sub.mu.Lock()
		if rev > sub.rev {
			sub.rev = rev
		}
		sub.mu.Unlock()
		select {
		case sub.signal <- struct{}{}:
		default: // a signal is already pending; it will carry the latest rev
		}
	}
}

// ---- logging ----

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *statusWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/api/") || r.URL.Path == "/api/events" {
			next.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: 200}
		next.ServeHTTP(sw, r)
		log.Printf("%s %s %d %s", r.Method, r.URL.Path, sw.status, time.Since(start).Round(time.Millisecond))
	})
}
