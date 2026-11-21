Status: STARTED (2026-11-15, developer)

# Development tasks

## T22 — Glass skin: tap on the BG circle opens the loop dialog (suspend loop / pump)
Priority: P3
Seen: user, 2026-11-14: "make the Glass circle with the BG, when clicked, open the loop dialog, the one where you
can suspend the loop and the pump."
What happens today (owner, from the code):
- Only the BG **number** can be tapped (`StatusAgoraCard.kt:255`, `Modifier.clickable { onOpenLoop() }`), not the
  whole circle (the TIR ring `Box`, :229-240).
- `onOpenLoop` opens the "glass_loop_detail" screen (`DashboardShellController.kt:1486-1495`), not the running mode
  dialog.
- The loop pill on the right (:324-333) opens the loop dashboard on tap, and `onOpenLoop` (loop detail) on long
  press.
- The dialog the user wants already exists: `openLoopDialog()` (`DashboardShellController.kt:789-795`) →
  `uiInteraction.openRunningModeScreen(activity)`, behind `ProtectionCheck.Protection.BOLUS`. The classic status
  card and loop indicator use it.
What should happen:
- A tap anywhere on the BG circle (the whole ring `Box`, with the number, unit, delta and arrow inside) opens the
  running mode screen. That is the same as `openLoopDialog()`, with the same BOLUS protection.
- Add a new command to `GlassHeroCommands` (for example `openRunningMode()`), and wire it in
  `DashboardShellController` to `openLoopDialog()`. Do not change `openLoop()`: the loop pill long press still
  uses it, so the loop detail screen stays reachable.
- Remove the `clickable` from the BG `Text`, so the tap goes to the circle and not to two places.
- Accessibility: give the circle a content description / `Role.Button` ("Loop and pump mode") with a string
  resource (en + pt-BR). The whole ring is the touch target, so it is big enough.
- Keep the long press on the circle free (no new action), unless the user asks.
Safety: the running mode screen can suspend the loop and disconnect / suspend the pump.
- Check that the MedLink path for these actions still works from this screen. "Disconnect pump" sets a 0 %
  TBR, which with MedLink means a real suspend through the TBR plan. "Suspend pump" for MedLink is the user
  suspend from T0.
- The protection (password / biometric) must ask before the screen opens, as today.
- No action may run on the tap itself: the tap only opens the screen.
Where: `StatusAgoraCard.kt` :229-260; `GlassHeroCommands.kt`; `GlassOverviewComposeEmbedded.kt:143`;
`DashboardShellController.kt` :789-795, :1486.
Tests needed: none for the UI. Build `:plugins:main`.
Field test:
1. On the Glass skin, tap the BG circle (on the ring, not only the number): the protection asks, then the
   loop / pump mode screen opens.
2. Suspend the loop for 1 h there: the loop status shows suspended. Resume it.
3. Long press the loop pill: the loop detail screen still opens.
Related: T17 (Glass layout; do not move the circle's tap area there).

### Developer notes (2026-11-15) — done, needs field test
- `GlassHeroCommands.openRunningMode()` → `DashboardShellController.openLoopDialog()` (BOLUS protection, then
  `uiInteraction.openRunningModeScreen`). `openLoop()` is not changed: the loop pill long press still opens the loop
  detail screen.
- `StatusAgoraCard`: the whole ring `Box` (clipped to a circle) is clickable, `Role.Button`, click label "Running
  mode" (existing `core:ui` string, already translated). The click was removed from the BG number. No long press.
- The tap only opens the screen. The MedLink paths for "disconnect pump" (0 % TBR → suspend plan) and "suspend
  pump" (T0 user suspend) are not changed.
- Build: `:plugins:main:compileFullDebugKotlin` OK.

### Fix (2026-11-17, user field test) — not committed, app not built yet
- The circle opened the ORIGINAL AAPS running mode screen (`openLoopDialog()` → `uiInteraction.openRunningModeScreen`).
  The user wants the Glass version (`glass_loop_detail`, `GlassLoopDetailScreen`: loop modes, suspend, pump disconnect),
  the screen the BG number opened before T22. `GlassHeroCommands.openRunningMode()` now calls `openLoop()` (same Glass
  screen as the loop pill, same BOLUS protection). The other `openLoopDialog()` callers (legacy status card / loop
  indicator / legacy hero) are not changed.
- **Field 2026-11-18 (user): PASS for step 1.** A tap on the Glass circle opens the right screen (Glass loop detail).
  Steps 2 (suspend the loop 1 h, then resume) and 3 (long press on the loop pill) not reported yet.
- **COMMITTED `3ba46b73e1` (2026-11-19, user request).** Only `DashboardShellController.kt` and `GlassHeroCommands.kt`.
- **Field 2026-11-19 (user): steps 2 (suspend loop / resume) and 3 (loop pill long press) PASS.**

### Change (2026-11-19, user request) — not committed, app not built yet
- The circle now works like the loop pill: tap = loop dashboard (`onOpenLoopDashboard`), long press = Glass loop
  detail (`onOpenRunningMode` -> `openLoop()`, BOLUS protection). `combinedClickable` with click labels
  "Loop Dashboard" / "Running mode" (existing strings). User chose this over "tap detail, long press dashboard".
- Field test: tap on the circle -> loop dashboard; long press -> protection, then loop detail.
- **Field 2026-11-19 (user): PASS. COMMITTED** (see `git log`, "the BG circle works like the loop pill").

## T23 — One status read per loop cycle; no S right after STOP / START
Priority: P2 (pump battery)
Seen: log analysis, 2026-11-14 (see `bugs-003.md` header).
What happens: status reads come in separate connections, close together. Example: 16:45:20 STOP, 16:46:03 `A`+`S`,
16:48:31 `S`, 16:50:50 START, 16:51:44 `A`+`S`. That is 5 connections in 6.5 min. In the hour there were 17
connections, and `S` was often sent twice in the same connection (part of it is B10).
What should happen:
- Read `S` at most **once per loop cycle** (5 min).
- When a plan operation (STOP / START / TBR bolus) is due, read `S` **in the same connection**, not in a new
  one.
- After a STOP / START, the answer of the command (and the `A` before it) already gives the pump state. Do not
  open a new connection only to read `S`.
- Skip a read when the last full status is fresh (proposal: less than 4 min old). Keep a maximum age: the
  existing 10-min safety read stays.
Safety:
- Before **any** bolus (SMB, TBR bolus, manual) and before the loop decides a TBR, the data must be fresh. Keep the
  `M` baseline before a bolus (T7) as it is.
- If the status is older than the limit, read it first. Never use old pump data as if it were new.
- The loop must see the reservoir and the running state at least every 10 min.
Where: `MedLinkMedtronicPumpPlugin.kt` (`refreshAnyStatusThatNeedsToBeRefreshed`, `readPumpData`, `scheduleNextRefresh`),
`MedLinkBLE.kt` (`needToAddConnectCommand`, the command picker).
Tests needed: a rule for "read S now?" with: last status 1 / 4 / 6 / 11 min old; an operation due; a bolus coming;
right after a STOP / START.
Field test: 1 h with a TBR plan. Count connections (`writing <OK+CONN>`) and `writing <S>`. Goal: about 12
connections and 12–15 S per hour (was 17 and 30). The loop never says the pump data is too old.
Related: B10, B11 (do them first; they explain part of the extra reads).

### Developer notes (2026-11-15) — done in part, needs field test
- The background `S` reads all come from `readPumpData` → `readPumpHistoryLogic` (`GetState`). The planned one is
  the `PumpHistory` refresh (about 5 min after the last good `S`).
- New rule `MedLinkStatusReadRule.statusReadWaitMs(now, lastStatusAt, lastStartStopAt)`: no planned `S` while the
  last `S` is less than 4 min old, or a STOP / START answer came less than 4 min ago. The wait never goes past
  10 min after the last good `S`.
- `refreshAnyStatusThatNeedsToBeRefreshed` (`PumpHistory` branch) uses it. When it skips, it logs
  "status read skipped: last S …s ago, last STOP/START …s ago, next in …s" and moves the `PumpHistory` time to the end
  of the wait (so the B11 check does not say "due" every 5 s).
- `lastStartStopAnswerAt` is set when the pump confirms a STOP (Suspended) or a START (Initialized).
- Not changed: the 10-min safety reads (`lastTryToConnect`, the 590 s check in `onStartScheduledPumpActions`),
  "clicked refresh", the start read, the bolus path (`M` baseline, T7). Plan steps run as before (B11).
- Not done: "read `S` in the same connection as a due plan step". The step and a due `S` already go to the BLE queue
  in the same refresh; joining them on purpose needs the BLE queue work of T25 / T26. User (2026-11-16): leave it
  for now, analyse it again after T25 / T26.
- Tests: 5 new cases in `MedLinkStatusReadRuleTest` (status 1 / 4 / 6 / 11 min old, no status yet, right after
  STOP / START, the 10 min limit).
- Field test: 1 h with a TBR plan. Count `writing <S>` and "status read skipped". The loop never says the pump data
  is old.
- **Field result 2026-11-17** (`logs/invalid_command.log`, build 07.23): 3 `S` in 13 min (about 14 / h, before 30 / h),
  "status read skipped" after a recent `S` and after STOP / START. Done.

## T24 — Keep the TBR plan when the new TBR gives the same insulin as the running one
Priority: P2 (pump battery: fewer STOP / START)
Seen: log analysis, 2026-11-14. The loop sent a new low TBR every 5 min (0.10, 0.32, 0.39 U/h … on a 1.2 U/h basal),
and the pump was stopped or started about every 5 min (7 times in the hour). User decision (2026-11-14):
**option A, keep the plan when the new TBR is about the same.**
What should happen: when the loop sends a TBR, compare it with the plan that runs now, over the new TBR's time. Keep
the running plan and its timing (do not rebuild, no new STOP / START) **only if the running plan gives the same or
less insulin than the new TBR, and less by under one pump step**:
`new − 1 step < running plan <= new`. The step is 0.1 U for the Veo bolus (`bolusSize`), or the suspend time that equals one step. The
loop still gets success, and the TBR record in AAPS is updated with the new rate and time as it is today.
- "Same" must be in **insulin over the remaining time**, not in % or U/h only. A 0.10 → 0.32 U/h change with
  suspends can be the same pump actions, or not.
- If the running plan would give **more** than the new TBR (even by less than one step) → rebuild. If it gives
  less by one step or more → rebuild. Any change to 0 U/h or from 0 U/h → rebuild.
- A TBR above 100 % (TBR boluses): same rule. Never keep a plan that would deliver more than the new TBR asks.
Safety: the main rule (DEV.md) is never more than asked. The delivered insulin of the kept plan must never be
more than the new TBR, over the new TBR's time. Keeping a plan that gives a bit **less** (under one step) is
OK. Log each decision: "TBR x U/h: plan kept (difference y U)" or "plan rebuilt".
Where: `MedLinkMedtronicPumpPlugin.setTempBasalAbsolute` / `setTempBasalPercent`, `TempBasalMicrobolusOperations`,
the plan builder.
Tests needed (first): the same rate → kept; the new TBR a bit higher (under one step) → kept; the new TBR a bit
lower (the running plan would give more) → rebuilt; less by one step or more → rebuilt; 0 ↔ non-zero → rebuilt; above 100 % → the TBR bolus total is never more than asked; the loop result is
success in all cases; the AAPS TBR record matches the new TBR.
Field test: 1 h at a stable BG with low TBRs. Count STOP / START: fewer than 7 per hour. Compare the insulin in the
treatments with the pump TDD (the Statistics TDD table "Pump" column): the difference stays as before.
Related: T23.

## T25 — Only one OK+CONN per connection
Priority: P3 (pump battery, small)
Seen: log analysis, 2026-11-14. Almost every connection writes OK+CONN twice, about 2 s apart (35 in the hour for
17 connections). User decision (2026-11-14): fix it.
What happens:
- 1st OK+CONN: the MedLink wakes up from Bluetooth and only answers its banner: "med-link wake up by
  bluetooth, version 01_05_2022, med-link battery: 64%, ok+conn or command". The pump is not contacted.
- 2nd OK+CONN: "ok+conn command confirmed for pump wakeup", then about 10 s later "confirmed pump wakeup,
  medtronic veo".
What should happen:
- After the banner, do not send a second OK+CONN when the banner already says "ok+conn or command".
- Option 1: send the real command (`S`, `A`, …) right away, if the MedLink then wakes the pump by itself.
- Option 2: send one OK+CONN only once the MedLink is awake.
- First check which one the MedLink supports. Look at the MedLink docs and the Bluetooth terminal
  screenshots in `~/projects/pessoal/bomba/Med-Link-v4/Zdjęcia i screeny/` (Polish; read and translate them),
  then test it.
Safety: the connect step (`BleConnectCommand`, `needToAddConnectCommand`, `MedLinkBLE.kt` :1931-2017, :969) also
decides when the pump is reachable (T20 counts failed connections). Keep "pump no response" detection working.
A bolus must never be sent before the pump wakeup is confirmed.
Where: `BleConnectCommand.kt`, `MedLinkBLE.kt` (`needToAddConnectCommand`, `ExecuteConnectCommand` :969, the Connect
executor removal :711, :1849).
Tests needed: banner then a command → one OK+CONN; "pump no response" → still counted (T20); bolus waits for
"confirmed pump wakeup".
Field test: 1 h: `writing <OK+CONN>` about one per connection (was 2). No new "invalid command" or failed
wakeup. Bolus and STOP / START still work.
Related: B10, T20.

## T26 — BLE drop after "ok+conn command confirmed": reconnect after 2 s and listen, do not send OK+CONN again
Priority: P2 (pump battery, and fewer "invalid command" / retries)
Seen: user, 2026-11-14: "if an OK command is confirmed by the pump but the Bluetooth connection drops, reconnect
to the MedLink 5 s after and wait for the messages. If the connection completes, go on with the commands,
otherwise send a new connection." Confirmed by the log (`logs/AndroidAPS.log`): 3 drops in 1 h between "ok+conn
command confirmed" and "confirmed pump wakeup" (16:35:47→16:35:53, 16:37:25→16:37:33, 17:05:33→17:05:42).
What happens today (log, 16:35):
```
16:35:47 ok+conn command confirmed for pump wakeup     (the MedLink is waking the pump)
16:35:53 Statechange 0 → Closing GATT → connecting medlink   (reconnect at once)
16:35:59 Statechange 2 (connected)
16:36:00 writing <OK+CONN>  → "invalid command"            (the MedLink is still busy with the wakeup)
16:36:10 Retrying 1 … writing <A> … 16:36:12 writing <STOP>
16:36:28 confirmed pump wakeup … ready                      (the first wakeup ends only now)
```
So after the drop, the MedLink goes on with the pump wakeup. Our new OK+CONN gets "invalid command", and we lose
10 s plus one more command.
What should happen:
- Remember the phase of the connection. When the link drops **after "ok+conn command confirmed" and before
  "confirmed pump wakeup" / "ready"**:
  1. Wait **2 s**, then reconnect to the MedLink (BLE only). (User, 2026-11-14: not 5 s. If the OK+CONN was not
     accepted, the MedLink powers down after about 5 s, so a 5 s wait can miss its messages.)
  2. **Do not write OK+CONN.** Only enable notifications and listen.
  3. React to the **first message** after the reconnect (user, 2026-11-14). Do not wait a fixed time:
     - **The new-connection banner** ("med-link wake up by bluetooth … ok+conn or command"): the MedLink is
       **not** busy with the wakeup any more. Send OK+CONN **at once**, as a normal new connection (one OK+CONN,
       see T25).
     - **Wakeup messages** ("for pump wakeup", "confirmed pump wakeup", "medtronic veo", "ready"): the old wakeup
       is still running. Wait for "confirmed pump wakeup" / "ready", then go on with the queued commands (`S`,
       `A`, STOP / START, …) as if there had been no drop.
     - **"time to powerdown 5s"**: the first OK+CONN was **not accepted** (user, 2026-11-14). Send OK+CONN at
       once. Do not count it as a failed connection for T20.
     - **"time to powerdown 10s" / "15s" / "20s"**: **the pump is connected** (user, 2026-11-14). Do not send
       OK+CONN; go on with the queued commands, as after "confirmed pump wakeup".
     - Read the number after the BLE chunks are joined. The answer comes in 20-byte pieces, and the log shows
       "time to powerdown 20time to p" and "time to powerdown 5" with the "s" in the next piece. Only the exact
       value 5 means "not accepted": "time to powerdown 5" must not match the start of "15" or "50".
     - These meanings are only for the **first messages after this reconnect**. In other places "time to
       powerdown N" keeps its meaning as today (for example "time to powerdown 5" with no answer to `M` → read
       `M` again).
     - **"pump no response"**: the wakeup failed. Make a normal new connection, and count one failed connection
       for T20.
  4. **Only if no message at all comes** (a fallback; proposal: 15 s after the reconnect, because the wakeup
     takes about 10 s in the log), make a normal new connection.
- A drop in other phases works as today.
Safety:
- This is only for the **connect / wakeup** phase.
- A drop after a BOLUS, STOP or START was confirmed keeps the existing rules: the bolus tracker checks with `M`,
  and START/STOP are verified. Never re-send a bolus because of this.
- Queued commands are not lost and not doubled. In particular, a STOP / START queued before the drop is
  written once.
- The bolus 5-minute lifetime rule still applies while we wait.
Where: `MedLinkBLE.kt` (`onConnectionStateChange` :2260-2280, `close` / `disconnect` :1325-1345, `medLinkConnect`
:991, `needToAddConnectCommand` :1931-1936, `ExecuteConnectCommand` :969); `BleConnectCommand.kt` (the answers
"ok+conn command confirmed", "confirmed pump wakeup", "pump no response").
Tests needed (first):
- a connection state rule: drop in the "wakeup confirmed" phase → reconnect after 2 s with no OK+CONN;
- after the reconnect "time to powerdown 5s" → OK+CONN at once, not counted as a failure;
- after the reconnect "time to powerdown 10s" / "15s" / "20s" → no OK+CONN, the queued commands go on;
- the number split over two BLE chunks ("time to powerdown 1" + "5s") → read as 15, not 5;
- after the reconnect the banner comes → OK+CONN is sent at once (no waiting);
- wakeup messages come → no OK+CONN, the queue goes on after "confirmed pump wakeup";
- no message at all in 15 s → one new OK+CONN;
- "pump no response" → new connection and the T20 count goes up by one;
- a drop in another phase → today's behaviour;
- queued STOP / START / bolus written once.
Field test: walk away a bit during a connection so the link drops (or switch Bluetooth off and on during the 10 s
wakeup). In the log: "reconnect after drop in wakeup phase, waiting for messages", **no** `writing <OK+CONN>` right
after the reconnect, "confirmed pump wakeup", then the queued command. No "invalid command".
Related: T25 (one OK+CONN), T20 (failed connection count), B10.
