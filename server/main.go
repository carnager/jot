// Command jot is the server for Jot, a self-hosted notes and todo app.
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"golang.org/x/term"
)

const usage = `jot — self-hosted notes & todos

Usage:
  jot serve [--addr :8080] [--data DIR] [--web-dir DIR]
  jot user add <name>          create a user (prompts for password)
  jot user passwd <name>       change password (logs out all devices)
  jot user del <name>          delete a user and all their items
  jot user list
  jot import-keep <user> <takeout.zip|dir>   import Google Keep notes from Takeout
  jot seed-demo <user>         fill an account with sample data for screenshots

Global flags (all commands, any position):
  --data DIR   data directory (default $JOT_DATA or ./data)

Environment: JOT_ADDR, JOT_DATA, JOT_PASSWORD (non-interactive user add/passwd)
`

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func main() {
	log.SetFlags(log.LstdFlags)
	fs := flag.NewFlagSet("jot", flag.ExitOnError)
	fs.Usage = func() { fmt.Fprint(os.Stderr, usage) }
	dataDir := fs.String("data", env("JOT_DATA", "data"), "data directory")
	addr := fs.String("addr", env("JOT_ADDR", ":8080"), "listen address")
	webDir := fs.String("web-dir", "", "serve web app from this directory instead of the embedded copy (development)")

	args := os.Args[1:]
	if len(args) == 0 {
		fs.Usage()
		os.Exit(2)
	}
	cmd := args[0]
	// Accept flags anywhere, e.g. "jot user add bob --data /srv/jot".
	var rest []string
	for remaining := args[1:]; ; {
		fs.Parse(remaining)
		remaining = fs.Args()
		if len(remaining) == 0 {
			break
		}
		rest = append(rest, remaining[0])
		remaining = remaining[1:]
	}

	if cmd == "help" || cmd == "-h" || cmd == "--help" {
		fs.Usage()
		return
	}
	if err := os.MkdirAll(*dataDir, 0o700); err != nil {
		log.Fatal(err)
	}
	store, err := OpenStore(*dataDir)
	if err != nil {
		log.Fatal(err)
	}
	defer store.Close()

	switch cmd {
	case "serve":
		err = serve(store, *addr, *webDir)
	case "user":
		err = userCmd(store, rest)
	case "import-keep":
		if len(rest) != 2 {
			err = errors.New("usage: jot import-keep <user> <takeout.zip|dir>")
		} else {
			err = importKeep(store, rest[0], rest[1])
		}
	case "seed-demo":
		if len(rest) != 1 {
			err = errors.New("usage: jot seed-demo <user>")
		} else {
			err = seedDemo(store, rest[0])
		}
	default:
		fs.Usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func serve(store *Store, addr, webDir string) error {
	web, err := webHandler(webDir)
	if err != nil {
		return err
	}
	if users, _ := store.Users(); len(users) == 0 {
		log.Printf("no users yet — create one with: jot user add <name>")
	}
	s := &Server{store: store, hub: NewHub()}
	go s.collectAttachmentsLoop()
	go s.reminderLoop()
	srv := &http.Server{
		Addr:              addr,
		Handler:           s.routes(web),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		srv.Shutdown(shutdown)
	}()
	log.Printf("jot listening on %s", addr)
	if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func userCmd(store *Store, args []string) error {
	if len(args) == 0 {
		return errors.New("usage: jot user add|passwd|del|list [name]")
	}
	needName := func() (string, error) {
		if len(args) != 2 || strings.TrimSpace(args[1]) == "" {
			return "", fmt.Errorf("usage: jot user %s <name>", args[0])
		}
		return strings.TrimSpace(args[1]), nil
	}
	switch args[0] {
	case "list":
		users, err := store.Users()
		if err != nil {
			return err
		}
		for _, u := range users {
			fmt.Printf("%s\t(created %s)\n", u.Name, u.Created.Format("2006-01-02"))
		}
		return nil
	case "add", "passwd":
		name, err := needName()
		if err != nil {
			return err
		}
		pw, err := readPassword()
		if err != nil {
			return err
		}
		if args[0] == "add" {
			err = store.AddUser(name, pw)
		} else {
			err = store.SetPassword(name, pw)
		}
		if err == nil {
			fmt.Println("ok")
		}
		return err
	case "del":
		name, err := needName()
		if err != nil {
			return err
		}
		if err := store.DeleteUser(name); err != nil {
			return err
		}
		fmt.Println("deleted", name)
		return nil
	}
	return fmt.Errorf("unknown user command %q", args[0])
}

func readPassword() (string, error) {
	if pw := os.Getenv("JOT_PASSWORD"); pw != "" {
		return pw, nil
	}
	if !term.IsTerminal(int(os.Stdin.Fd())) {
		line, err := bufio.NewReader(os.Stdin).ReadString('\n')
		pw := strings.TrimRight(line, "\r\n")
		if pw == "" {
			if err == nil {
				err = errors.New("empty password")
			}
			return "", err
		}
		return pw, nil
	}
	fmt.Fprint(os.Stderr, "Password: ")
	a, err := term.ReadPassword(int(os.Stdin.Fd()))
	fmt.Fprintln(os.Stderr)
	if err != nil {
		return "", err
	}
	fmt.Fprint(os.Stderr, "Repeat: ")
	b, err := term.ReadPassword(int(os.Stdin.Fd()))
	fmt.Fprintln(os.Stderr)
	if err != nil {
		return "", err
	}
	if string(a) != string(b) {
		return "", errors.New("passwords don't match")
	}
	if len(a) < 6 {
		return "", errors.New("password too short (min 6)")
	}
	return string(a), nil
}
