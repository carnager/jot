package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"math"
	"time"
)

// seedDemo fills an account with realistic sample data for screenshots. Dates are
// relative to today, so the views look current whenever it runs. Existing demo
// items (ids "demo-*") are replaced; nothing else is touched.
func seedDemo(store *Store, username string) error {
	u, err := store.UserByName(username)
	if err != nil {
		return fmt.Errorf("user %q: %w", username, err)
	}
	now := time.Now()
	nowMs := now.UnixMilli()
	today := civilDate(now)
	day := func(offset int) string { return today.AddDate(0, 0, offset).Format("2006-01-02") }
	at := func(offset int, hhmm string) string { return day(offset) + "T" + hhmm }

	// Today's timed tasks are spread over what's left of the day, so they look
	// upcoming (not overdue) whenever the screenshots are taken. Very late at
	// night there's no room left and they keep their usual times.
	const todaySlots = 7
	nowMin := now.Hour()*60 + now.Minute()
	start := max(8*60, (nowMin/15+2)*15)
	step := (23*60 + 45 - start) / todaySlots / 5 * 5
	slot := 0
	todayAt := func(usual string) string {
		defer func() { slot++ }()
		if step < 10 {
			return at(0, usual)
		}
		m := start + slot*step
		return at(0, fmt.Sprintf("%02d:%02d", m/60, m%60))
	}

	// Images: stylised landscapes, generated so the demo needs no files.
	scenes := map[string]scene{
		"dusk":    {sky: [2]string{"#2b2d5c", "#e8a0a8"}, sun: "#fff1d6", sunX: .7, sunY: .42, hills: []string{"#5b4b7a", "#3a2f55", "#22203a"}},
		"morning": {sky: [2]string{"#8fd3f4", "#fdf6e3"}, sun: "#ffd166", sunX: .25, sunY: .3, hills: []string{"#9bc49a", "#5f9a6b", "#3d6b4f"}},
		"sunset":  {sky: [2]string{"#f6b26b", "#fde2c0"}, sun: "#fff4e0", sunX: .55, sunY: .5, hills: []string{"#d4835a", "#a85a3c", "#6e3a2a"}},
		"sea":     {sky: [2]string{"#a7d8f0", "#e8f6fb"}, sun: "#fffbe6", sunX: .8, sunY: .25, hills: []string{"#5fa8c8", "#2f7fa6", "#1d5a7c"}},
		"forest":  {sky: [2]string{"#cfe7d4", "#f4f1e6"}, sun: "#f7d488", sunX: .4, sunY: .35, hills: []string{"#7fae80", "#4f8460", "#2e5a43"}},
		"night":   {sky: [2]string{"#0f1530", "#3b3f74"}, sun: "#f4f1d0", sunX: .3, sunY: .3, hills: []string{"#2a2f55", "#1c2040", "#11142b"}},
		"lisbon":  {sky: [2]string{"#7ec8e3", "#fff3d6"}, sun: "#ffe08a", sunX: .65, sunY: .3, hills: []string{"#e9a873", "#c9754f", "#8c4a3a"}},
		"concert": {sky: [2]string{"#3a0f4f", "#c2408a"}, sun: "#ffd6f0", sunX: .5, sunY: .55, hills: []string{"#5a1f6b", "#3a1248", "#1e0a2a"}},
	}
	img := func(name string, w, h int) map[string]any {
		id := "demo-img-" + name
		data, err := renderScene(scenes[name], w, h)
		if err == nil {
			_, err = store.PutAttachment(u.ID, id, "image/jpeg", data)
		}
		if err != nil {
			panic(err)
		}
		return map[string]any{"id": id, "w": w, "h": h, "mime": "image/jpeg"}
	}

	n := 0
	var changes []json.RawMessage
	add := func(fields map[string]any) {
		n++
		it := map[string]any{
			"id": fmt.Sprintf("demo-%02d", n), "kind": "note", "title": "", "body": "", "checks": []any{},
			"color": "", "labels": []string{}, "pinned": false, "archived": false, "done": false, "doneAt": 0,
			"due": "", "remind": true, "snoozedUntil": "", "repeat": nil,
			"sort": float64(nowMs - int64(n)*60000), "createdAt": nowMs - int64(n)*3600000,
			"updatedAt": nowMs - int64(n)*60000, "trashedAt": 0, "deleted": false,
		}
		for k, v := range fields {
			it[k] = v
		}
		raw, _ := json.Marshal(it)
		changes = append(changes, raw)
	}
	checks := func(items ...string) []map[string]any {
		out := make([]map[string]any, 0, len(items))
		for i, t := range items {
			done := len(t) > 2 && t[:2] == "x "
			if done {
				t = t[2:]
			}
			out = append(out, map[string]any{"id": fmt.Sprintf("c%d", i), "text": t, "done": done, "indent": 0})
		}
		return out
	}
	list := func(f map[string]any, items ...string) map[string]any {
		f["kind"] = "list"
		f["checks"] = checks(items...)
		return f
	}
	labels := func(l ...string) []string { return l }
	rep := func(freq string, extra map[string]any) map[string]any {
		r := map[string]any{"freq": freq, "interval": 1}
		for k, v := range extra {
			r[k] = v
		}
		return r
	}
	wd := func(d int) string {
		return []string{"su", "mo", "tu", "we", "th", "fr", "sa"}[today.AddDate(0, 0, d).Weekday()]
	}

	// ---- Today: overdue, all day, morning, afternoon, evening, and a few done ----
	add(map[string]any{"title": "Renew passport", "due": day(-2), "color": "red", "labels": labels("admin"),
		"body": "Photos are in the drawer. Appointment form online."})
	add(map[string]any{"title": "Reply to Lena about the venue", "due": at(-1, "16:00"), "labels": labels("work")})
	add(map[string]any{"title": "Water the plants", "due": day(0), "color": "green", "labels": labels("home"),
		"repeat": rep("day", map[string]any{"interval": 3})})
	add(map[string]any{"title": "Pay rent", "due": day(0), "labels": labels("finance"),
		"repeat": rep("month", map[string]any{"monthDay": today.Day()})})
	add(map[string]any{"title": "Morning run", "due": todayAt("07:00"), "labels": labels("health"),
		"repeat": rep("week", map[string]any{"byDay": []string{"mo", "we", "fr"}}), "done": false})
	add(map[string]any{"title": "Standup", "due": todayAt("09:30"), "color": "blue", "labels": labels("work"),
		"repeat": rep("week", map[string]any{"byDay": []string{"mo", "tu", "we", "th", "fr"}})})
	add(map[string]any{"title": "Design review: onboarding flow", "due": todayAt("14:00"), "color": "purple", "labels": labels("work"),
		"body": "Bring the empty-state sketches and the motion prototype.", "alerts": []int{0, 15}})
	add(map[string]any{"title": "Pick up package at the post office", "due": todayAt("16:30"), "color": "orange",
		"body": "Tracking 00340 4341 2201 · bring ID"})
	add(list(map[string]any{"title": "Groceries for the weekend", "due": todayAt("18:00"), "color": "yellow", "labels": labels("shopping"), "pinned": true},
		"Oat milk", "Sourdough", "Tomatoes", "Basil", "x Parmesan", "Lemons"))
	add(map[string]any{"title": "Call mom", "due": todayAt("19:00"), "color": "pink", "labels": labels("family")})
	add(map[string]any{"title": "Take vitamins", "due": todayAt("21:30"), "labels": labels("health"), "repeat": rep("day", nil)})
	add(map[string]any{"title": "Book the plumber", "due": day(0), "done": true, "doneAt": nowMs - 3*3600000, "labels": labels("home")})
	add(map[string]any{"title": "Send invoice #2026-114", "due": at(0, "10:00"), "done": true, "doneAt": nowMs - 2*3600000, "labels": labels("work")})
	add(map[string]any{"title": "Return library books", "due": day(0), "done": true, "doneAt": nowMs - 3600000})

	// ---- Upcoming ----
	add(map[string]any{"title": "Dentist", "due": at(1, "10:00"), "color": "teal", "labels": labels("health"), "alerts": []int{0, 60}})
	add(map[string]any{"title": "Team retro", "due": at(1, "15:00"), "color": "blue", "labels": labels("work"),
		"body": "What went well, what didn't, one experiment for next sprint.", "alerts": []int{0, 10}})
	add(map[string]any{"title": "Book flights to Lisbon", "due": day(2), "labels": labels("travel")})
	add(map[string]any{"title": "Quarterly taxes", "due": at(3, "10:00"), "color": "red", "labels": labels("finance"), "alerts": []int{0, 1440}})
	add(list(map[string]any{"title": "Farmers market", "due": at(4, "09:00"), "color": "green", "labels": labels("shopping")},
		"Apples", "Eggs (10)", "Flowers", "Honey"))
	add(map[string]any{"title": "Bin day", "due": day(5), "labels": labels("home"), "repeat": rep("week", map[string]any{"byDay": []string{wd(5)}})})
	add(map[string]any{"title": "Mum's birthday", "due": day(6), "color": "pink", "labels": labels("family"),
		"body": "Flowers + the book she mentioned.", "repeat": rep("year", nil), "alerts": []int{0, 2880}})
	add(map[string]any{"title": "Car service", "due": at(9, "08:30"), "labels": labels("admin")})
	add(map[string]any{"title": "Concert: Bonobo", "due": at(12, "20:00"), "color": "purple", "labels": labels("fun"),
		"images": []any{img("concert", 1200, 800)}})
	add(map[string]any{"title": "Dinner with Sam & Priya", "due": at(4, "19:30"), "labels": labels("family")})

	// ---- Notes ----
	add(list(map[string]any{"title": "Trip to Lisbon", "color": "teal", "labels": labels("travel"), "pinned": true,
		"images": []any{img("lisbon", 1200, 800)}},
		"Passport", "Charger", "Sunscreen", "Camera", "x Book flights", "x Hotel"))
	add(map[string]any{"title": "Book ideas", "color": "purple", "labels": labels("reading"), "pinned": true,
		"body": "The Ministry for the Future\nPiranesi\nKlara and the Sun\nTomorrow, and Tomorrow, and Tomorrow"})
	add(map[string]any{"title": "Weekend walk", "labels": labels("travel"),
		"images": []any{img("dusk", 1200, 800), img("morning", 1200, 800), img("sea", 1200, 800), img("forest", 1200, 800)}})
	add(map[string]any{"title": "Sunset from the balcony", "color": "orange", "labels": labels("travel"),
		"images": []any{img("sunset", 1200, 900)}})
	add(map[string]any{"title": "Shakshuka", "color": "red", "labels": labels("recipes"),
		"body": "2 tbsp olive oil, 1 onion, 1 red pepper, 3 garlic cloves, 1 tsp cumin, 1 tsp paprika, 800 g tomatoes, 6 eggs, feta, parsley. Simmer 15 min, crack eggs in, cover 8 min."})
	add(map[string]any{"title": "Pasta al limone", "color": "brown", "labels": labels("recipes"),
		"body": "Zest 2 lemons into butter, toss with spaghetti, pasta water and parmesan. Black pepper."})
	add(map[string]any{"title": "Wi-Fi at the cabin", "labels": labels("home"),
		"body": "Network: Seeblick-5G\nPassword: on the fridge"})
	add(map[string]any{"title": "Meeting notes — Q4 planning", "color": "blue", "labels": labels("work"),
		"body": "Goals\n- ship the importer v2\n- reduce on-call load\n- hire one more engineer\n\nOpen questions: contractor budget? migration timeline?"})
	add(list(map[string]any{"title": "Gift ideas", "color": "pink", "labels": labels("family")},
		"Ceramic pour-over (Sam)", "Field notes (Priya)", "Concert tickets (mom)"))
	add(list(map[string]any{"title": "Packing list — hiking", "color": "orange", "labels": labels("travel")},
		"Boots", "Rain jacket", "Headlamp", "First aid kit", "Snacks", "Water filter", "x Map", "Sunglasses"))
	add(list(map[string]any{"title": "Books to read", "color": "gray", "labels": labels("reading")},
		"Piranesi", "The Overstory", "Project Hail Mary", "x Sea of Tranquility"))
	add(map[string]any{"title": "Small steps, every day.", "color": "yellow"})
	add(map[string]any{"title": "Apartment", "color": "brown", "labels": labels("home"),
		"body": "Landlord: Mr. Okafor · +49 30 1234567\nHeating check: every October\nMeter number 0042-7781"})
	add(map[string]any{"title": "Podcast queue", "labels": labels("reading"),
		"body": "Hardcore History — Blueprint for Armageddon\nThe Rest Is History — the Normans"})
	add(list(map[string]any{"title": "Bike maintenance", "color": "green", "labels": labels("home")},
		"Clean chain", "Check brake pads", "x Pump tyres"))
	add(map[string]any{"title": "Night sky over the lake", "labels": labels("travel"), "images": []any{img("night", 1200, 800)}})
	add(map[string]any{"title": "Ideas for the blog", "color": "teal", "labels": labels("writing"),
		"body": "• Why plain text still wins\n• Self-hosting for normal people\n• A week without notifications"})
	add(map[string]any{"title": "Japanese phrases", "labels": labels("travel"),
		"body": "Sumimasen — excuse me\nArigatou gozaimasu — thank you\nOkaikei onegaishimasu — the bill, please"})

	// ---- Archive, trash ----
	add(map[string]any{"title": "Moving checklist 2025", "archived": true, "labels": labels("home")})
	add(map[string]any{"title": "Old gym schedule", "archived": true, "color": "gray"})
	add(map[string]any{"title": "Draft: complaint to the airline", "trashedAt": nowMs - 86400000})

	if _, _, _, err := store.Sync(u.ID, 1<<62, changes); err != nil {
		return err
	}
	fmt.Printf("seeded %d demo items for %s\n", len(changes), username)
	return nil
}

type scene struct {
	sky        [2]string
	sun        string
	sunX, sunY float64
	hills      []string
}

func hexColor(s string) color.RGBA {
	var r, g, b uint8
	fmt.Sscanf(s, "#%02x%02x%02x", &r, &g, &b)
	return color.RGBA{r, g, b, 255}
}

func mix(a, b color.RGBA, t float64) color.RGBA {
	l := func(x, y uint8) uint8 { return uint8(float64(x) + (float64(y)-float64(x))*t) }
	return color.RGBA{l(a.R, b.R), l(a.G, b.G), l(a.B, b.B), 255}
}

// renderScene draws a flat illustrated landscape: gradient sky, a sun, layered hills.
func renderScene(sc scene, w, h int) ([]byte, error) {
	im := image.NewRGBA(image.Rect(0, 0, w, h))
	top, bottom := hexColor(sc.sky[0]), hexColor(sc.sky[1])
	sun := hexColor(sc.sun)
	sx, sy, sr := sc.sunX*float64(w), sc.sunY*float64(h), float64(h)*0.11
	type ridge struct {
		base, amp, freq, phase float64
		c                      color.RGBA
	}
	var ridges []ridge
	for i, c := range sc.hills {
		f := float64(i)
		ridges = append(ridges, ridge{base: .58 + f*.12, amp: .06 - f*.012, freq: 1.4 + f*.9, phase: f * 1.7, c: hexColor(c)})
	}
	for y := range h {
		sky := mix(top, bottom, float64(y)/float64(h))
		for x := range w {
			c := sky
			if d := math.Hypot(float64(x)-sx, float64(y)-sy); d < sr {
				c = sun
			} else if d < sr*1.6 {
				c = mix(sun, sky, (d-sr)/(sr*.6)*.6+.4)
			}
			for _, r := range ridges {
				fx := float64(x) / float64(w)
				ry := (r.base + r.amp*math.Sin(fx*math.Pi*r.freq+r.phase) + r.amp*.4*math.Sin(fx*math.Pi*r.freq*2.3+r.phase*2)) * float64(h)
				if float64(y) > ry {
					c = r.c
				}
			}
			im.SetRGBA(x, y, c)
		}
	}
	var buf bytes.Buffer
	err := jpeg.Encode(&buf, im, &jpeg.Options{Quality: 88})
	return buf.Bytes(), err
}
