package main

import (
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"io/fs"
	"mime"
	"net/http"
	"os"
	"path"
	"strings"
)

//go:embed all:web
var embeddedWeb embed.FS

// webHandler serves the single-page app. Unknown paths without a file
// extension fall back to index.html so client-side routes survive a reload.
// With dir != "" files are read from disk on every request (development).
func webHandler(dir string) (http.Handler, error) {
	var fsys fs.FS
	if dir != "" {
		fsys = os.DirFS(dir)
	} else {
		sub, err := fs.Sub(embeddedWeb, "web")
		if err != nil {
			return nil, err
		}
		fsys = sub
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		name := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if name == "" || strings.HasPrefix(name, "test/") {
			name = "index.html"
		}
		data, err := fs.ReadFile(fsys, name)
		if err != nil {
			if path.Ext(name) != "" {
				http.NotFound(w, r)
				return
			}
			name = "index.html"
			if data, err = fs.ReadFile(fsys, name); err != nil {
				http.Error(w, "web app missing", http.StatusInternalServerError)
				return
			}
		}
		sum := sha256.Sum256(data)
		etag := `"` + hex.EncodeToString(sum[:8]) + `"`
		h := w.Header()
		h.Set("ETag", etag)
		// Files aren't fingerprinted, so always revalidate; the ETag makes that cheap.
		h.Set("Cache-Control", "no-cache")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "same-origin")
		if name == "index.html" {
			h.Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
		}
		if ct := mime.TypeByExtension(path.Ext(name)); ct != "" {
			h.Set("Content-Type", ct)
		}
		if match := r.Header.Get("If-None-Match"); match == etag {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		if r.Method == http.MethodHead {
			return
		}
		w.Write(data)
	}), nil
}

func init() {
	// Some minimal distros lack /etc/mime.types; make sure the essentials are right.
	mime.AddExtensionType(".js", "text/javascript; charset=utf-8")
	mime.AddExtensionType(".mjs", "text/javascript; charset=utf-8")
	mime.AddExtensionType(".css", "text/css; charset=utf-8")
	mime.AddExtensionType(".svg", "image/svg+xml")
	mime.AddExtensionType(".webmanifest", "application/manifest+json")
}
