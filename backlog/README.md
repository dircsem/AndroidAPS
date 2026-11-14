# Backlog

The project owner (skill `project-owner`) writes bugs and tasks here. Two queues, kept apart:

- **Development** (app code, done by the `android-developer` skill): `bugs-NNN.md` and `tasks-NNN.md`.
- **Engineering** (3D case, hardware, done by the `3d-engineer` skill): `engineering-NNN.md`.

## Files and folders

- `bugs-NNN.md`: development bugs. `tasks-NNN.md`: development tasks. `engineering-NNN.md`: engineering
  tasks and bugs (one file kind, ids `E<n>`). `NNN` is a 3 digit number: `001`, `002`, ...
- **The folder is the status** (user, 2026-11-14):

  | folder | status | who writes |
  |---|---|---|
  | `backlog/` | OPEN: nobody works on it yet | only the owner adds items |
  | `backlog/working/` | someone is working on it | only the developer / engineer who moved it |
  | `backlog/done/` | every item is done (or moved to a newer file) | nobody |

- The first line of every file is a status line with the date, for people who read the file alone:
  `Status: OPEN`, `Status: STARTED (YYYY-MM-DD, developer)` or `Status: DONE (YYYY-MM-DD)`.
  If the line and the folder do not agree, **the folder wins**.
- Move files with `git mv` when the file is tracked by git, else with `mv`. A move is one step, so the owner
  never sees a file half taken.

## Owner rules

1. Write only to files in `backlog/` (the root). Never to `working/` or `done/`, not even to fix a typo.
2. Write to the highest-numbered file of that kind in the root. If there is none, create the next number.
   The next number is one more than the highest number of that kind in **any** folder (root, `working/`,
   `done/`), so a number is never used twice. First line: `Status: OPEN`.
3. Check that the file is still in the root just before you write. If it was moved, create the next file.
4. A bug is something that works wrong now. A task is new work or a change. If one bug needs several tasks,
   put the bug in `bugs-NNN.md` and the tasks under it in the same file, so they are done together.
5. Item ids are global and never reused: `B<n>` for bugs, `T<n>` for tasks, `E<n>` for engineering.
   Continue from the highest id in any file of any folder. Files `bugs-001` / `tasks-001` used `T0`–`T13` and
   "Bug 1–4" without ids, so new ids start at `B5` and `T14`.
6. Anything about the case, STL files, printing or hardware goes to `engineering-NNN.md`, never to the
   development files. If a change needs both (for example a new connector needs a case opening and app
   code), write one item in each queue and link them by id.
7. News about an item that is in `working/` or `done/` (an answer from the user, a new log): write a new
   item in the root that points to the old id. Tell the user, so they can pass it on.

## Developer rules (development queue)

1. Order: all bugs first, then tasks. Inside each kind: P1, then P2, then P3. With the same priority: the
   lowest file number first, then the order in the file.
2. **Before you start work on a file, move it to `backlog/working/`** and set its first line to
   `Status: STARTED (date, developer)`. Take the whole file: the owner will not add to it any more.
3. Work only from files in `working/`. Write your notes there under each item: what you found, what you
   changed, tests run, "done, needs field test".
4. **When every item of the file is done, move it to `backlog/done/`** and set `Status: DONE (date)`. If you
   stop with items not done, leave the file in `working/` and say which items are left.
5. Never mark an item as working without the user's field test. Write "done, needs field test".

## Engineer rules (engineering queue)

The same as the developer, for `engineering-NNN.md`: move the file to `backlog/working/` before you start
(`Status: STARTED (date, engineer)`), and to `backlog/done/` when every item is done. Order: P1 first, then
the order in the file. "Works" only after the user has printed and tried it.

## Item format

```
## B5 — <short title>
Priority: P1 | P2 | P3      (P1 = can over-deliver insulin or stop insulin with no alarm)
Seen: <field / log / code review>, <date>
What happens: ...
What should happen: ...
Where: <file:line>, ...
Tests needed: ...
Field test: <steps on the pump, what to look for in the log>
```

Engineering items use the same format with `## E<n>`. Use `Checks needed` (mesh check, sections, fit in mm)
in place of `Tests needed`, and say which print and measure the user must do as the field test.
