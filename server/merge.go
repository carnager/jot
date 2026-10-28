package main

import (
	"bytes"
	"encoding/json"
)

// fieldGroups maps each merge group (SPEC §2b) to the item fields it covers.
// Checks are handled separately: per check by "u", ordered by the "order" group.
var fieldGroups = map[string][]string{
	"title":    {"title"},
	"body":     {"body"},
	"kind":     {"kind"},
	"color":    {"color"},
	"labels":   {"labels"},
	"pinned":   {"pinned"},
	"archived": {"archived"},
	"schedule": {"done", "doneAt", "due", "remind", "snoozedUntil", "repeat"},
	"sort":     {"sort"},
	"images":   {"images"},
	"trash":    {"trashedAt"},
}

// managedFields are set explicitly by the merge; everything else comes from
// the side with the newer updatedAt.
var managedFields = func() map[string]bool {
	m := map[string]bool{"checks": true, "fm": true, "updatedAt": true, "deleted": true, "rev": true}
	for _, fields := range fieldGroups {
		for _, f := range fields {
			m[f] = true
		}
	}
	return m
}()

type mergeSide struct {
	obj       map[string]json.RawMessage
	updatedAt int64
	fm        map[string]int64 // nil for clients that don't track field times
	incoming  bool
}

func newMergeSide(obj map[string]json.RawMessage, incoming bool) mergeSide {
	s := mergeSide{obj: obj, incoming: incoming}
	json.Unmarshal(obj["updatedAt"], &s.updatedAt)
	if raw, ok := obj["fm"]; ok {
		json.Unmarshal(raw, &s.fm)
	}
	// Tracking clients stamp fm (or a check's u) and updatedAt together, so updatedAt
	// never exceeds every stamp. If it does, a client that doesn't know fm edited the item
	// (keeping a stale fm), and trusting fm would throw that edit away.
	if s.fm != nil {
		var latest int64
		for _, t := range s.fm {
			latest = max(latest, t)
		}
		var checks []struct {
			U int64 `json:"u"`
		}
		json.Unmarshal(obj["checks"], &checks)
		for _, c := range checks {
			latest = max(latest, c.U) // ticking a check stamps only its u
		}
		if s.updatedAt > latest {
			s.fm = nil
		}
	}
	return s
}

// at is when this side last changed a group; untracked groups fall back to
// updatedAt, which turns the merge into whole-item last-write-wins.
func (s mergeSide) at(group string) int64 {
	if t, ok := s.fm[group]; ok {
		return t
	}
	return s.updatedAt
}

// mergeItems merges an incoming change into the stored copy of the same item.
// Ties go to the incoming side.
func mergeItems(stored, incoming map[string]json.RawMessage) map[string]json.RawMessage {
	st, in := newMergeSide(stored, false), newMergeSide(incoming, true)

	newer := in
	if st.updatedAt > in.updatedAt {
		newer = st
	}
	out := map[string]json.RawMessage{}
	for k, v := range newer.obj {
		if !managedFields[k] {
			out[k] = v
		}
	}

	for group, fields := range fieldGroups {
		win := in
		if st.at(group) > in.at(group) {
			win = st
		}
		for _, f := range fields {
			if v, ok := win.obj[f]; ok {
				out[f] = v
			}
		}
	}

	if checks := mergeChecks(st, in); checks != nil {
		out["checks"] = checks
	}

	if isTrue(stored["deleted"]) || isTrue(incoming["deleted"]) {
		out["deleted"] = json.RawMessage("true")
	} else if v, ok := newer.obj["deleted"]; ok {
		out["deleted"] = v
	}

	out["updatedAt"] = mustJSON(max(st.updatedAt, in.updatedAt))
	if st.fm != nil || in.fm != nil {
		fm := map[string]int64{}
		for _, side := range []mergeSide{st, in} {
			for k, t := range side.fm {
				fm[k] = max(fm[k], t)
			}
		}
		out["fm"] = mustJSON(fm)
	}
	return out
}

// mergeChecks unions checks by id when both sides track field times;
// otherwise the side that wins the "order" group keeps its list as is.
func mergeChecks(st, in mergeSide) json.RawMessage {
	_, stHas := st.obj["checks"]
	_, inHas := in.obj["checks"]
	if !stHas && !inHas {
		return nil
	}
	orderWin, other := in, st
	if st.at("order") > in.at("order") {
		orderWin, other = st, in
	}
	if st.fm == nil || in.fm == nil {
		if v, ok := orderWin.obj["checks"]; ok {
			return v
		}
		return other.obj["checks"]
	}

	type check struct {
		id  string
		u   int64
		raw json.RawMessage
	}
	parse := func(s mergeSide) []check {
		var list []json.RawMessage
		json.Unmarshal(s.obj["checks"], &list)
		out := make([]check, 0, len(list))
		for _, raw := range list {
			var meta struct {
				ID string `json:"id"`
				U  *int64 `json:"u"`
			}
			if json.Unmarshal(raw, &meta) != nil || meta.ID == "" {
				continue
			}
			c := check{id: meta.ID, u: s.updatedAt, raw: raw}
			if meta.U != nil {
				c.u = *meta.U
			}
			out = append(out, c)
		}
		return out
	}
	winList, otherList := parse(orderWin), parse(other)

	best := map[string]check{}
	consider := func(c check, preferOnTie bool) {
		if cur, ok := best[c.id]; !ok || c.u > cur.u || (c.u == cur.u && preferOnTie) {
			best[c.id] = c
		}
	}
	// Ties go to the incoming side, like every other field.
	for _, c := range winList {
		consider(c, orderWin.incoming)
	}
	for _, c := range otherList {
		consider(c, other.incoming)
	}

	merged := make([]json.RawMessage, 0, len(best))
	seen := map[string]bool{}
	for _, list := range [][]check{winList, otherList} {
		for _, c := range list {
			if !seen[c.id] {
				seen[c.id] = true
				merged = append(merged, best[c.id].raw)
			}
		}
	}
	return mustJSON(merged)
}

func isTrue(raw json.RawMessage) bool { return bytes.Equal(bytes.TrimSpace(raw), []byte("true")) }

func mustJSON(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}
