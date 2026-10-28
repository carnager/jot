package main

import (
	"encoding/json"
	"os"
	"testing"
	"time"
)

// The same vectors the web and Android clients run (spec/repeat.json).
func TestRepeatVectors(t *testing.T) {
	raw, err := os.ReadFile("../spec/repeat.json")
	if err != nil {
		t.Fatal(err)
	}
	var spec struct {
		Cases []struct {
			Name   string `json:"name"`
			Due    string `json:"due"`
			Repeat Repeat `json:"repeat"`
			Now    string `json:"now"`
			Expect struct {
				Due  string `json:"due"`
				Done bool   `json:"done"`
			} `json:"expect"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(raw, &spec); err != nil {
		t.Fatal(err)
	}
	for _, c := range spec.Cases {
		now, err := time.Parse("2006-01-02T15:04", c.Now)
		if err != nil {
			t.Fatalf("%s: %v", c.Name, err)
		}
		next, done := completeRepeat(c.Due, c.Repeat, now)
		if done != c.Expect.Done || (!done && next != c.Expect.Due) {
			t.Errorf("%s: got due=%q done=%v, want due=%q done=%v", c.Name, next, done, c.Expect.Due, c.Expect.Done)
		}
	}
}
