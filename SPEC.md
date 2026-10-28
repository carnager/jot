# Jot — notes + todos, self-hosted

Jot replaces Google Keep and works as a todo app too. It has three parts:

- `server/`: a Go server. It stores the data in SQLite and serves the sync API and the web app as one binary.
- `server/web/`: the desktop-first web app. It is plain ES modules with no build step and is embedded into the binary.
- `android/`: a native Kotlin + Jetpack Compose app. It works offline first, and reminders fire locally (no Google services).

**The design goal is speed.** Capturing something takes one gesture and one line of text. Every common action
(complete, snooze, pin, label, archive, set date) is reachable in one tap, swipe or keystroke. If a feature
adds friction to the common path, it's wrong.

---

## 1. Data model

Everything is an **item**. A note becomes a todo when it has a due date or gets checked off, so there is
no "note vs task" decision at capture time.

```jsonc
{
  "id": "01JB2XQ3M4...",        // client-generated, unique string (ULID or UUIDv4). Never changes.
  "kind": "note",               // "note" (free text body) | "list" (checklist in `checks`)
  "title": "Buy milk",
  "body": "",                   // plain text; clients render URLs as links. Used when kind=note.
  "checks": [                   // used when kind=list. Order = display order.
    { "id": "c1", "text": "Milk", "done": false, "indent": 0 }   // indent 0 or 1
  ],
  "color": "",                  // "" | red | orange | yellow | green | teal | blue | purple | pink | brown | gray
  "labels": ["shopping"],       // lowercase, no '#', no spaces (use '-'), unique, sorted
  "pinned": false,
  "archived": false,
  "done": false,                // completed todo. Repeating items never become done (they advance).
  "doneAt": 0,                  // ms epoch, 0 = never
  "due": "",                    // "" | "2026-10-29" (all-day) | "2026-10-29T09:30" (timed)
                                //   floating local wall-clock time (no timezone, follows the device)
  "remind": true,               // fire a notification when due (timed: at due time; all-day: at the
                                //   user's "all-day reminder time" setting, default 09:00)
  "snoozedUntil": "",           // "" | "2026-10-29T10:30": the next notification fires here instead of
                                //   at due. Cleared when due changes or the item is completed.
  "repeat": null,               // null | Repeat (see §4)
  "sort": 1730000000000,        // manual order key (float). Higher = earlier in the list.
                                //   New items: Date.now(). Reorder: midpoint of the neighbours.
  "createdAt": 1730000000000,   // ms epoch
  "updatedAt": 1730000000000,   // ms epoch, set by the client on EVERY change (used for last-write-wins)
  "images": [                  // optional, shown above title/body in cards and the editor (Keep-style)
    { "id": "01JB...", "w": 1600, "h": 1200, "mime": "image/jpeg" }   // id = attachment id (§2a)
  ],
  "trashedAt": 0,               // ms epoch, 0 = not in trash. Clients hide trash > 30 days and set deleted.
  "deleted": false,             // tombstone, gone for good. Kept so deletes sync.
  "rev": 0                      // set by the server only. Clients send back whatever they have; it's ignored.
}
```

Rules:
- Unknown fields must be kept when an item is round-tripped (forward compatibility). The server stores the whole JSON.
- `labels` is the only source of labels. The label list in the UI is the union over all non-deleted items.
- An item is a **task** if `due != ""` or `done == true`. Task views only show tasks. Notes views show everything.

### Views (same in both clients)
| View | Shows | Order |
|---|---|---|
| **Today** | not done, not archived, not trashed, `due` date ≤ today (overdue items first, red) | overdue, then all-day, then by time |
| **Upcoming** | not done, not archived/trashed, has `due` > today | grouped by day headers ("Tomorrow", "Fri 31 Oct", …) |
| **Notes** (home) | not archived/trashed, not done | pinned first, then by `sort` desc. Shown as a Keep-style card grid |
| **Label X** | like Notes, filtered by label | same |
| **Done** | done == true, not trashed | doneAt desc |
| **Archive** | archived, not trashed | updatedAt desc |
| **Trash** | trashedAt > 0, not deleted | trashedAt desc. Actions: restore, delete forever, empty trash |

Search covers title, body, check texts and labels. It is case-insensitive and diacritic-insensitive, and every
whitespace-separated term must match.

---

## 2. Sync API

Base: `https://host/api`. JSON everywhere. Auth: `Authorization: Bearer <token>`. The web app can use the
`jot_session` cookie instead (HttpOnly, SameSite=Lax), which the server sets on web login.

### `POST /api/login`
`{"username":"..","password":"..","device":"Pixel 9"}` → `200 {"token":"..","username":".."}` or `401`.
Also sets the cookie. `POST /api/logout` revokes the current token/cookie.

### `GET /api/me`
→ `{"username":".."}` or `401`.

### `POST /api/sync` (the one endpoint for data)
Request:
```json
{ "since": 0, "changes": [ <item>, <item> ] }
```
- `since`: the highest `rev` the client has seen (0 = full download).
- `changes`: items the client changed locally since its last successful sync (full objects).

Server behaviour, per change: the change is stored if there is no stored item with that id, or if
`change.updatedAt >= stored.updatedAt` (last write wins). Stored items get a fresh, globally increasing `rev`.
Changes that lose LWW are ignored (the newer server copy comes back in the response because its rev > since).

Response:
```json
{ "rev": 1234, "items": [ <every item of this user with rev > since, including the ones just written> ] }
```
The client replaces local items with the returned ones, **except** for items it changed locally *during* the
request (keep those dirty and push them next time). Then it stores `since = rev`.

Limits: a body of 20 MB at most. Items larger than 1 MB are rejected with 413.

### `GET /api/events` (Server-Sent Events)
It sends `event: rev\ndata: <rev>\n\n` whenever this user's data changes (send at most 4 per second), plus a
`: ping` comment every 25 s. On an event, a client with a lower rev runs a sync. The web app uses this all
the time. Android uses it while the app is in the foreground.

### Attachments (§2a)
Images are stored next to items. An item only references them by id in `images`.
- `PUT /api/attachments/{id}`: the raw image bytes as the body, with `Content-Type` image/jpeg | image/png | image/webp | image/gif.
  `{id}` is client-generated (same format as item ids, `[A-Za-z0-9_-]{1,64}`), so an image captured offline can be
  referenced right away and uploaded later. It's idempotent: a second PUT with the same id is accepted and changes nothing.
  The maximum size is 25 MB. Responds `{"id","w","h","mime","size"}`.
- `GET /api/attachments/{id}` returns the original. `GET /api/attachments/{id}?w=480` returns a JPEG thumbnail at most 480px wide
  (allowed widths: 240, 480, 960, 1600), generated and cached by the server. Responses are
  `Cache-Control: private, max-age=31536000, immutable`, because ids never change. Returns 404 if not uploaded (yet).
  The web app's `<img>` tags authenticate with the session cookie.
- **Clients downscale before uploading**: long edge at most 2560px, JPEG quality 85, with EXIF orientation applied and metadata
  stripped (no GPS leaks). Animated GIFs are uploaded as they are.
- Order: the client uploads first, then pushes the item. Offline, the item syncs whenever it can, and the upload is retried
  in the background. Other devices show a placeholder until the image appears (retry on 404).
- The server deletes attachments that no item references (trashed items still count as references, deleted ones don't)
  once they're older than 24 h. Re-importing from Keep brings the Takeout images along.

### Errors
`{"error":"message"}` with the matching HTTP status. A `401` means the client returns to login.

### Accounts
There is no public sign-up. Accounts are managed with the binary:
`jot user add <name>` (asks for a password), `jot user passwd <name>`, `jot user list`, `jot user del <name>`.

---

## 3. Quick-add grammar (natural-language capture)

Both clients parse the capture line **while typing** and show the recognised tokens highlighted inline
(or as chips under the input). Tapping or clicking a recognised token, or pressing Backspace right after it,
turns it back into literal text. Recognised tokens are removed from the title.
English and German are both always active.

Tokens (case-insensitive, whole words only):

- **Labels**: `#word` → label `word` (lowercased). Several are allowed.
- **Dates**:
  - `today` `heute` · `tonight` `heute abend` (→ today 19:00) · `tomorrow` `tmrw` `morgen` · `übermorgen` `uebermorgen` (+2)
  - weekdays: `mon monday mo montag`, `tue tuesday di dienstag`, `wed wednesday mi mittwoch`,
    `thu thursday do donnerstag`, `fri friday fr freitag`, `sat saturday sa samstag`, `sun sunday so sonntag`.
    This means the next such day, strictly after today. `next <weekday>` / `nächsten <weekday>` is the same.
    Two-letter German forms (mo, di, mi, do, fr, sa, so) are only recognised when followed by a time or
    preceded by `am`/`on`, to avoid false positives ("do the dishes").
  - `in N days|day|d|tagen|tag`, `in N weeks|week|w|wochen|woche`, `in N months|monaten|monat`, `in N hours|h|stunden|stunde` (timed: now + N h, rounded up to 5 min), `in N min|minutes|minuten` (same).
  - `next week` `nächste woche` → next Monday · `next month` `nächsten monat` → 1st of next month · `weekend` `wochenende` → next Saturday (today if today is Saturday).
  - Absolute: `2026-10-29`, `29.10.`, `29.10`, `29.10.2026`, `29.10.26`, `10/29` (US m/d only with a slash),
    `29 oct`, `oct 29`, `29. oktober`, `october 29`. Without a year: the next future occurrence.
  - The keywords `on`, `am`, `at`, `um`, `by`, `bis` right before a date or time are eaten too.
- **Times** (only directly after a date, or alone): `9am` `9 am` `9pm` `9:30` `09:30` `21:00` `21h` `9 uhr` `um 9` `at 9` `noon` `mittag` `midnight`.
  A bare number counts as a time only with `at`/`um` or `uhr`/`am`/`pm`.
  - Time without a date → today if that time is still in the future, else tomorrow.
  - Date without a time → all-day.
- **Repeat**:
  - `every day` `daily` `täglich` `jeden tag` → day/1
  - `every weekday` `weekdays` `werktags` → week/1 byDay mo-fr
  - `every week` `weekly` `wöchentlich` `jede woche` → week/1 (byDay = due's weekday)
  - `every <weekday>[, <weekday> and <weekday>]` `jeden <wochentag>` `jeden montag und donnerstag` → week/1 byDay [...]
  - `every N days|weeks|months|years` `alle N tage|wochen|monate|jahre` → interval N
  - `every month` `monthly` `monatlich` `jeden monat` → month/1 · `every year` `yearly` `annually` `jährlich` `jedes jahr` → year/1
  - `every 15th` / `jeden 15.` → month/1 on day 15
  - A suffix `after done` / `nach erledigung` → fromCompletion = true
  - A repeat with no explicit date sets due to the first occurrence that isn't in the past (all-day: date ≥ today;
    timed: datetime > now). For example, weekly with byDay → the next matching day, which may be today. Plain daily → today.
- **Checklist**: if the text contains newlines (pasted or Shift+Enter), the first line is the title and every
  following non-empty line becomes a check (a leading `- `, `* `, `[ ] ` or `[x] ` is stripped, `[x]` = done) → kind=list.
  A title ending in `:` followed by `a, b, c` (comma list) → a checklist too: `Shopping: milk, eggs, bread`.
- **Pin**: a trailing ` !` (space + exclamation mark) → pinned.

Anything else stays in the title. If nothing is left of the title, the title is the raw text minus the labels.

**Test vectors** live in `spec/quickadd.json`, with a fixed "now" given as a local datetime. Both clients run
them in their unit tests. When the grammar changes, update the vectors first.

---

## 4. Repeats

```jsonc
"repeat": {
  "freq": "day" | "week" | "month" | "year",
  "interval": 1,              // ≥ 1
  "byDay": ["mo","we"],       // week only, optional. Empty = weekday of due.
  "monthDay": 15,             // month only, optional (1..31, clamps to the month's last day). 0/absent = day of due.
  "fromCompletion": false,    // true: the next due counts from the completion date, not from the old due
  "until": ""                 // "" | "YYYY-MM-DD": inclusive end date
}
```

**Completing a repeating item** (checkbox, swipe, or the notification's Done action) does NOT set `done`.
Instead:
1. `base` = completion date if `fromCompletion`, otherwise the current `due` date.
2. `next` = the first occurrence strictly after `base` (keeping the time of day of `due`). If not fromCompletion,
   keep advancing while `next` is not in the future (timed: datetime ≤ now; all-day: date ≤ today), so overdue
   repeats catch up in one tap.
3. If `until` is set and `next` > until → complete normally (`done=true`).
4. Otherwise `due=next`, `snoozedUntil=""`, every `checks[*].done=false`, `doneAt=now` (last completion).
5. The UI shows a toast/snackbar "Next: Fri 31 Oct" with **Undo**.

The occurrence rules: day → +interval days. week with byDay → the next listed weekday. After the last listed
weekday of a week, jump `interval` weeks ahead (weeks start Monday). month → +interval months on
`monthDay ?? day(due)`, clamped to the month's length. year → +interval years (29 Feb → 28 Feb).

Test vectors: `spec/repeat.json`.

---

## 5. Reminders & notifications

- **Android** schedules exact alarms locally (`AlarmManager.setExactAndAllowWhileIdle`, permission
  `USE_EXACT_ALARM`) for every item that has `remind`, is not done/archived/trashed and has a due date.
  The fire time is `snoozedUntil` or due (timed) or due date at the all-day time. Alarms are rescheduled after every
  local change or sync, on boot, on time-zone or time change, and on app update. When a fire time is in the
  past and was never shown, the notification appears right away, at most once per item and fire time.
  The notification has the title and a body preview, and its actions are **Done**, **Snooze 1 h** and **Tomorrow**
  (snooze presets can be set in Settings). Tapping it opens the item.
  One channel is "Reminders" (high importance) with grouping.
- **Web** shows `Notification`s while a tab is open (it asks permission once from Settings) and keeps a
  red overdue badge in the tab title / favicon.
- The server never sends notifications (there's no push dependency).

---

## 6. UX requirements (both clients)

The fastest paths:
- **Capture**: the main screen has an always-visible capture bar. Enter saves and keeps focus for the next
  item (rapid entry). Android also has a Quick Settings tile, a launcher shortcut, a home-screen widget and
  a share target that open a floating capture sheet with the keyboard already up. Web: `n` or `q` from anywhere focuses capture.
- **Complete**: tap the round checkbox on any task. Android: swipe right. Web: `x` / `Space`.
- **Reschedule**: Android swipes left to a date sheet with one-tap chips (Later today · Tonight · Tomorrow ·
  Next Mon · Weekend · Pick…) plus a natural-language field ("fri 9am"). Web: `d` opens the same popover, `t` = tomorrow.
- **Label/pin/color/archive/delete**: one tap from the card or row overflow, the multi-select bar or a keyboard
  shortcut (`l`, `p`, `c`, `a`/`e`, `#`, `Del`).
- **Undo** for every destructive or completing action (a snackbar with Undo, `Ctrl+Z` on web).
- Editing a note is in place (web: a right-side panel; Android: a full-screen editor with shared-element feel).
  Changes save automatically. There's no save button.

Look & feel: Material 3 / Material You on Android (dynamic color, light/dark). The web app follows the same
spirit, adapted for desktop: a dense but airy layout, real keyboard navigation and light/dark themes. Note
colors are pastel tints that adapt to dark mode (Keep-like).
