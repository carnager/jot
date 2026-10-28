package main

import (
	"archive/zip"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// keepNote is the subset of a Google Takeout Keep JSON file we understand.
type keepNote struct {
	Color                   string `json:"color"`
	IsTrashed               bool   `json:"isTrashed"`
	IsPinned                bool   `json:"isPinned"`
	IsArchived              bool   `json:"isArchived"`
	Title                   string `json:"title"`
	TextContent             string `json:"textContent"`
	UserEditedTimestampUsec int64  `json:"userEditedTimestampUsec"`
	CreatedTimestampUsec    int64  `json:"createdTimestampUsec"`
	Labels                  []struct {
		Name string `json:"name"`
	} `json:"labels"`
	ListContent []struct {
		Text      string `json:"text"`
		IsChecked bool   `json:"isChecked"`
	} `json:"listContent"`
	Attachments []struct {
		FilePath string `json:"filePath"`
		MIMEType string `json:"mimetype"`
	} `json:"attachments"`
}

var keepColors = map[string]string{
	"RED": "red", "ORANGE": "orange", "YELLOW": "yellow", "GREEN": "green",
	"TEAL": "teal", "BLUE": "blue", "CERULEAN": "blue", "PURPLE": "purple",
	"PINK": "pink", "BROWN": "brown", "GRAY": "gray",
}

type keepFile struct {
	name string
	data []byte
}

// keepSource reads attachment files that sit next to the note JSON in a Takeout export.
type keepSource func(dir, name string) ([]byte, error)

// readKeepFiles collects the note JSON files from a Takeout zip or an
// extracted directory.
func readKeepFiles(src string) ([]keepFile, keepSource, error) {
	var out []keepFile
	isNote := func(name string) bool {
		return strings.EqualFold(filepath.Ext(name), ".json") && !strings.HasSuffix(strings.ToLower(name), "labels.json")
	}
	if strings.EqualFold(filepath.Ext(src), ".zip") {
		zr, err := zip.OpenReader(src)
		if err != nil {
			return nil, nil, err
		}
		byName := map[string]*zip.File{}
		for _, f := range zr.File {
			byName[f.Name] = f
		}
		open := func(dir, name string) ([]byte, error) {
			f := byName[dir+"/"+name]
			if f == nil {
				return nil, os.ErrNotExist
			}
			rc, err := f.Open()
			if err != nil {
				return nil, err
			}
			defer rc.Close()
			return io.ReadAll(io.LimitReader(rc, maxAttachmentBytes+1))
		}
		for _, f := range zr.File {
			if !isNote(f.Name) || !strings.Contains(f.Name, "Keep/") {
				continue
			}
			rc, err := f.Open()
			if err != nil {
				return nil, nil, err
			}
			data, err := io.ReadAll(rc)
			rc.Close()
			if err != nil {
				return nil, nil, err
			}
			out = append(out, keepFile{f.Name, data})
		}
		return out, open, nil
	}
	err := filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || !isNote(p) {
			return err
		}
		data, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		out = append(out, keepFile{p, data})
		return nil
	})
	open := func(dir, name string) ([]byte, error) { return os.ReadFile(filepath.Join(dir, name)) }
	return out, open, err
}

func normalizeLabel(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	s = strings.TrimPrefix(s, "#")
	return strings.Join(strings.Fields(s), "-")
}

// importKeep converts a Takeout export into items for the given user. IDs are
// derived from the file name, so importing the same export twice updates
// instead of duplicating.
func importKeep(store *Store, username, src string) error {
	u, err := store.UserByName(username)
	if err != nil {
		return fmt.Errorf("user %q: %w", username, err)
	}
	files, openFile, err := readKeepFiles(src)
	if err != nil {
		return err
	}
	now := time.Now().UnixMilli()
	var changes []json.RawMessage
	skipped, images, missing := 0, 0, 0
	for _, f := range files {
		var n keepNote
		if err := json.Unmarshal(f.data, &n); err != nil || (n.CreatedTimestampUsec == 0 && n.UserEditedTimestampUsec == 0) {
			skipped++
			continue
		}
		sum := sha1.Sum([]byte(filepath.Base(f.name)))
		updated := n.UserEditedTimestampUsec / 1000
		created := n.CreatedTimestampUsec / 1000
		if created == 0 {
			created = updated
		}
		if updated == 0 {
			updated = created
		}
		labels := []string{}
		seen := map[string]bool{}
		for _, l := range n.Labels {
			if nl := normalizeLabel(l.Name); nl != "" && !seen[nl] {
				seen[nl] = true
				labels = append(labels, nl)
			}
		}
		sort.Strings(labels)
		item := map[string]any{
			"id":           "keep-" + hex.EncodeToString(sum[:10]),
			"kind":         "note",
			"title":        n.Title,
			"body":         n.TextContent,
			"checks":       []any{},
			"color":        keepColors[n.Color],
			"labels":       labels,
			"pinned":       n.IsPinned,
			"archived":     n.IsArchived,
			"done":         false,
			"doneAt":       0,
			"due":          "",
			"remind":       true,
			"snoozedUntil": "",
			"repeat":       nil,
			"sort":         float64(created),
			"createdAt":    created,
			"updatedAt":    updated,
			"trashedAt":    0,
			"deleted":      false,
		}
		var imgs []Attachment
		for _, att := range n.Attachments {
			if !strings.HasPrefix(att.MIMEType, "image/") {
				missing++ // audio recordings
				continue
			}
			data, err := openFile(filepath.Dir(f.name), att.FilePath)
			if err != nil || len(data) > maxAttachmentBytes {
				missing++
				continue
			}
			isum := sha1.Sum([]byte(filepath.Base(f.name) + "/" + att.FilePath))
			a, err := store.PutAttachment(u.ID, "keep-"+hex.EncodeToString(isum[:10]), att.MIMEType, data)
			if err != nil {
				missing++
				continue
			}
			images++
			imgs = append(imgs, a)
		}
		if len(imgs) > 0 {
			refs := make([]map[string]any, len(imgs))
			for i, a := range imgs {
				refs[i] = map[string]any{"id": a.ID, "w": a.W, "h": a.H, "mime": a.MIME}
			}
			item["images"] = refs
		}
		if n.IsTrashed {
			item["trashedAt"] = now
		}
		if len(n.ListContent) > 0 {
			checks := make([]map[string]any, 0, len(n.ListContent))
			for i, c := range n.ListContent {
				checks = append(checks, map[string]any{"id": fmt.Sprintf("k%d", i), "text": c.Text, "done": c.IsChecked, "indent": 0})
			}
			item["kind"] = "list"
			item["checks"] = checks
			item["body"] = ""
		}
		raw, err := json.Marshal(item)
		if err != nil {
			return err
		}
		changes = append(changes, raw)
	}
	if len(changes) == 0 {
		return fmt.Errorf("no Keep notes found in %s (expected Takeout/Keep/*.json)", src)
	}
	if _, _, _, err := store.Sync(u.ID, 1<<62, changes); err != nil {
		return err
	}
	fmt.Printf("imported %d notes for %s", len(changes), username)
	if skipped > 0 {
		fmt.Printf(", skipped %d unreadable files", skipped)
	}
	if images > 0 {
		fmt.Printf(", %d images", images)
	}
	if missing > 0 {
		fmt.Printf(" (%d attachments skipped: audio or missing files)", missing)
	}
	fmt.Println()
	return nil
}
