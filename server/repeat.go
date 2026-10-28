package main

import (
	"strings"
	"time"
)

// Floating local dates and times as items store them (SPEC §1): "2026-10-29" or "2026-10-29T09:30".
// All arithmetic happens on civil dates in UTC so DST never shifts a day.

type Repeat struct {
	Freq           string   `json:"freq"`
	Interval       int      `json:"interval"`
	ByDay          []string `json:"byDay"`
	MonthDay       int      `json:"monthDay"`
	FromCompletion bool     `json:"fromCompletion"`
	Until          string   `json:"until"`
}

var weekdayCodes = map[string]time.Weekday{
	"mo": time.Monday, "tu": time.Tuesday, "we": time.Wednesday, "th": time.Thursday,
	"fr": time.Friday, "sa": time.Saturday, "su": time.Sunday,
}

// parseDue splits a due string into its civil date (UTC midnight) and minutes after midnight (-1 = all-day).
func parseDue(due string) (day time.Time, minutes int, ok bool) {
	if len(due) == 10 {
		d, err := time.Parse("2006-01-02", due)
		return d, -1, err == nil
	}
	t, err := time.Parse("2006-01-02T15:04", due)
	if err != nil {
		return time.Time{}, 0, false
	}
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC), t.Hour()*60 + t.Minute(), true
}

func formatDue(day time.Time, minutes int) string {
	if minutes < 0 {
		return day.Format("2006-01-02")
	}
	return day.Add(time.Duration(minutes) * time.Minute).Format("2006-01-02T15:04")
}

func civilDate(t time.Time) time.Time {
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, time.UTC)
}

func daysIn(year int, month time.Month) int {
	return time.Date(year, month+1, 0, 0, 0, 0, 0, time.UTC).Day()
}

// mondayIndex: Monday = 0 … Sunday = 6 (weeks start Monday).
func mondayIndex(d time.Weekday) int { return (int(d) + 6) % 7 }

// nextOccurrence is the first occurrence strictly after d. anchor is the original due
// date, whose day-of-month (or month/day) monthly and yearly repeats keep.
func nextOccurrence(d time.Time, r Repeat, anchor time.Time) time.Time {
	n := max(r.Interval, 1)
	switch r.Freq {
	case "week":
		if len(r.ByDay) == 0 {
			return d.AddDate(0, 0, 7*n)
		}
		var days []int
		for _, code := range r.ByDay {
			if wd, ok := weekdayCodes[strings.ToLower(code)]; ok {
				days = append(days, mondayIndex(wd))
			}
		}
		if len(days) == 0 {
			return d.AddDate(0, 0, 7*n)
		}
		cur := mondayIndex(d.Weekday())
		first, next := 7, 7
		for _, x := range days {
			first = min(first, x)
			if x > cur {
				next = min(next, x)
			}
		}
		monday := d.AddDate(0, 0, -cur)
		if next < 7 {
			return monday.AddDate(0, 0, next)
		}
		return monday.AddDate(0, 0, 7*n+first)
	case "month":
		day := r.MonthDay
		if day <= 0 {
			day = anchor.Day()
		}
		y, m := d.Year(), d.Month()+time.Month(n)
		first := time.Date(y, m, 1, 0, 0, 0, 0, time.UTC)
		return time.Date(first.Year(), first.Month(), min(day, daysIn(first.Year(), first.Month())), 0, 0, 0, 0, time.UTC)
	case "year":
		y := d.Year() + n
		return time.Date(y, anchor.Month(), min(anchor.Day(), daysIn(y, anchor.Month())), 0, 0, 0, 0, time.UTC)
	default: // "day"
		return d.AddDate(0, 0, n)
	}
}

// completeRepeat applies SPEC §4 to a repeating item's due at local time now.
// It returns the next due, or done=true when the repeat has ended.
func completeRepeat(due string, r Repeat, now time.Time) (next string, done bool) {
	dueDay, minutes, ok := parseDue(due)
	if !ok {
		return "", true
	}
	today := civilDate(now)
	nowMinutes := now.Hour()*60 + now.Minute()
	base := dueDay
	if r.FromCompletion {
		base = today
	}
	d := nextOccurrence(base, r, dueDay)
	notFuture := func(d time.Time) bool {
		if minutes < 0 {
			return !d.After(today)
		}
		return d.Before(today) || (d.Equal(today) && minutes <= nowMinutes)
	}
	if !r.FromCompletion {
		for i := 0; notFuture(d) && i < 10000; i++ {
			d = nextOccurrence(d, r, dueDay)
		}
	}
	if r.Until != "" {
		if until, err := time.Parse("2006-01-02", r.Until); err == nil && d.After(until) {
			return "", true
		}
	}
	return formatDue(d, minutes), false
}
