Status: OPEN

# Bugs 004

Source: `logs/temp_basal_stuck.log`, build 2026.11.15-1929, night of 2026-11-16. The file covers only
**01:25:51 → 01:50:20** (20484 lines). The user saw the pump suspended until about 06:00.

Timeline seen in the log (line numbers are in `logs/temp_basal_stuck.log`):

| time | line | what |
|---|---|---|
| 01:26:11 | 72 | TBR bolus 0.2 U from the old plan (delivered at 01:27:19, line 1770) |
| 01:26:47 | 1286, 1299 | loop: absolute 0.0 U/h for 30 min. BG 118, falling |
| 01:26:47 | 1301 | plan "suspend 30 min / active 0 min, starts suspended" → SUSPEND 01:26:47, REACTIVATE 01:56:47 |
| 01:31, 01:36, 01:41 | 4700, 9001, 12832 | loop: 0 U/h again, "Temp basal set correctly" (plan kept). BG 115 → 110 → 106 |
| 01:46:43 | 16825, 16831 | loop: 0.32 U/h for 30 min. BG 105 |
| 01:46:43 | 16833 | new plan "suspend 5 min / active 5 min, starts active for 4 min"; START sent |
| 01:47:14 | — | pump: "start command confirmed … to normal state" (the start worked) |
| 01:46:44 → 01:50:20 | 16870 → end | the plan shows only `SUSPEND` at 01:50:43 (`duration=17`); the old REACTIVATE 01:56:47 is gone (last seen line 16684) |
| 01:50:20 | 20470-20484 | pump: "stop command confirmed … switching pump to suspend state". **The log ends here.** |

The log has no line after this stop: no REACTIVATE, no loop run, no BG. So the log can not show why the pump
stayed suspended. It does show that, up to 01:50, a start was **not** overwritten: the 01:46 start was sent and
confirmed.

---

## B12 — Pump stayed suspended from about 01:50 to 06:00 after a TBR plan stop (no resume, no alarm)

**Status: cause found, fix in the working tree (not committed). Field test needed.**
Analysis with the full logs (`temp_basal_stuck.log` 01:25-02:08, `temp_basal_stuck_1.log` 02:08-02:57,
`temp_basal_stuck_2.log` 02:57-04:39) and the logcat crash (2026-11-17):
| time | what |
|---|---|
| 01:50 / 02:00 | plan STOP and REACTIVATE both worked (the first theory, "app died at 01:50", was wrong) |
| 02:30:34 | plan STOP (pump suspended) |
| 02:31:44 | loop 0.61 U/h, new plan; 02:31:47 REACTIVATE taken, START sent at 02:31:48 |
| 02:31:50 | **app crash**, PID 14851: `NoSuchElementException` in `LinkedBlockingDeque.getFirst` <- `temporaryBasal` <- `isSuspended` <- `LoopPlugin.runningModePreCheck` (main thread). `isEmpty()` then `first` while the plan thread took the last step. |
| 02:32:05 | app started again (PID 22786). The plan was only in memory: the REACTIVATE was lost. |
| 02:32:44 | `initProfile` replayed all 21 stored TBRs from `pumpSyncStorage` (some from 2026-11-07): 21 STOPs, the last one a 0 % TBR for 30 min. Repeated on every profile read (11 times, about every 12 min). |
| 02:32 -> 04:39 | status read saw the pump suspended 61 times. `handlePumpStatus` logged "starting pump" 27 times, but the `startPump` call was commented out (since `eec7220fa8`, 2026-11-08). No alarm. |
| 02:32:51 -> 04:39 | no loop run: every calculation starts, none ends (see B13) |
Fix (working tree):
- H: one null-safe read of the first plan step everywhere (`TempBasalMicrobolusOperations.firstOrNull()`); the step runner is one synchronized call (`takeDueStep`): no crash on an empty plan, no step taken twice by the two refresh threads. `!!` on the TBR in the STOP / START answer made null-safe.
- A: `initProfile` (TBR replay) removed.
- B / D (B15): `MedLinkResumeRule`: pump suspended + last STOP was automatic (`MedLinkAutoStopAt`, saved, kept over a restart) + no plan REACTIVATE to come (5 min grace) -> START; still suspended 15 min after the stop, or a plan REACTIVATE more than 10 min late -> START + alarm "Pump still suspended". A suspend by the user (app or pump) is never undone. Nothing while T21 waits for the user.
- C: REACTIVATE always goes through `startPump()` (T13 check), not skipped on an old "Running" state.
- E: at app start the log has the reason of the last app exit (`ApplicationExitInfo`).
- F: the git remote URL is logged and stored without user / token.
Tests: medtronic 283/283 (`MedLinkResumeRuleTest`, `TempBasalMicrobolusOperationsStepTest` new), rileylink 536/536, `:app:assembleFullDebug` OK.
Still open from the list below: B14 (plan math). Plan carry: at 02:31:44 the new plan put its REACTIVATE 1 s after its STOP (STOP and START 4 s apart).
Priority: P1 (insulin stopped for about 4 hours with no alarm)
Seen: field, night of 2026-11-16, `logs/temp_basal_stuck.log`
What happens:
- 01:50:20 the plan of the 0.32 U/h TBR (made 01:46:43) stops the pump. This step is planned and correct.
- The log ends at the same second (line 20484). Nothing more is logged until the file was taken at 07:05.
- The user found the pump suspended at about 06:00. Nothing started it, and nobody was told.
- With 0.32 U/h for 30 min, the pump should have been running again before 02:17 at the latest. The loop should
  also have made a new TBR every 5 min.
What should happen: every SUSPEND step has a REACTIVATE after it, and the REACTIVATE runs on time. If the pump
is still suspended after the planned end (+ a small margin), the user gets an alarm, even if the plan or the
loop is gone.
Where to look (the developer must confirm which one it was):
1. **The app or the plan stopped at 01:50:20.** The log ends at the same second as the stop answer. Check the
   AAPS log files after this one (rolled / zipped files in the AAPS `logs` folder), Android `logcat` / crash
   reports, and Nightscout (loop status, device status) for 01:50 → 06:00. If the process died or froze, then no
   REACTIVATE can ever run: see B15.
2. **The plan had no REACTIVATE.** At 01:46:43 the old plan (with REACTIVATE 01:56:47) was cleared
   (`MedLinkMedtronicPumpPlugin.kt:2215`) and a new one built (`:2216`, `scheduleSuspension` `:2547`,
   `buildSuspensionScheduler` `:2398`, `mergeExtraOperations` `:2518`, `applyPlanCarry` `:3178`). The log shows only
   the first step. Check that the new plan really had a REACTIVATE after the 01:50:43 SUSPEND (see B14 for one way
   steps go missing).
3. **The REACTIVATE ran but the start failed or was skipped.** `refreshAnyStatusThatNeedsToBeRefreshed` REACTIVATE
   branch (around `:1160`): `startPump` is skipped when `medLinkPumpStatus.pumpRunningState` is already `Running`.
   The stop at 01:50:20 was sent 23 s **before** its release time (01:50:43). Check that the state after this
   early stop is `TempBasalSuspended` and not a stale `Running`, and that the SUSPEND step was completed only
   after the stop answer. Old task T13 (`backlog/working/tasks-001.md`, "verify resume after 0% temp basal")
   covers the start retry. Check that it is really done in this build.
4. Two threads (`Thread-36`, `Thread-37`) both run `doWeHaveAnyStatusNeededRefreshing` every 5 s, all through the
   log. Check that two refresh loops can't run the same step twice, or cancel each other's steps.
Tests needed:
- Plan for 0.32 U/h / 30 min with basal 0.75, made at 01:46:43 while an older 0 % plan is running: the plan has
  at least one REACTIVATE after every SUSPEND, and the last step is a REACTIVATE at or before the TBR end.
- A TBR change (0 % → 43 %) while the pump is suspended: no SUSPEND is left without a REACTIVATE after it.
- REACTIVATE due, app state `Running` but the last `A` answer is older than the last stop: the start is sent.
- A SUSPEND sent early (before its release time): the step is completed only after "suspend state", and the
  next REACTIVATE still runs.
Field test: set a 0 % TBR from the loop, then (after 15-20 min) a TBR around 40 %. Keep the phone on the charger
with the screen off. In the log, look for: "plan: suspend … / active …", every SUSPEND followed by a REACTIVATE
step, "start command confirm … normal state" after each planned suspend, and no stop without a later start.

## B13 — The loop did not set a TBR above 100 % when BG went up during the night

**Update 2026-11-17:** the loop did not run at all from 02:32:51 to 04:39 (BG 90 -> 156). After the app restart at
02:32:05 the first calculation finished (02:32:21); after that every calculation starts ("Starting calculation worker")
and no `PrepareGraphDataWorker` / `PostCalculationWorker` ends (0 of 28 in `temp_basal_stuck_2.log`, 8 of 8 before
the crash). The dashboard says "no BG data". Cause still unknown. Maybe linked to the TBR replay of B12 (many TBR rows
-> a DBChange calculation each), but calculations from a new BG hang too. Check again after the B12 fix: if the loop
runs after a restart, close B13.
Priority: P1 (insulin stopped while BG rose, no alarm)
Seen: field, night of 2026-11-16, user report. **Not in `logs/temp_basal_stuck.log`**: in this file BG only falls
(118 → 105, lines 659 … 16564) and the loop asks for 0 and 0.32 U/h, which is right.
What happens: the user says that BG went up after the pump stopped and the loop never asked for more than 100 %.
The loop looked stuck.
What should happen: every 5 min the loop runs, sees the rising BG and the suspended pump, and sets a higher TBR.
With MedLink, a TBR above the basal must also start the pump (`checkPumpNeedToBeStarted` `:2368`,
`clearTempBasal` `:2380`, "bolusingbasal" branch `:2234`).
Where to look:
- First get the log after 01:50:20 (see B12). If the app or the loop did not run, B13 is the same root cause
  as B12. Then close B13 as a duplicate.
- If the loop did run: check the loop result (`OpenAPSAIMIPlugin … Result: RT … rate=`) and `LoopPlugin.applyTBRRequest`
  for each run. Check whether a TBR > basal goes to `scheduleTempBasalBolus` and starts the pump, or whether
  `keepTbrPlanIfSame` (`:2203`, T24) keeps a suspend plan by mistake. Check whether the AIMI "Current temp" the
  loop sees matches the pump (at 01:31 the loop sees `rate=0.0`, line 4577). A wrong current temp can block a change.
Tests needed (after the cause is known): a TBR > 100 % while a suspend plan is running and the pump is suspended
→ the old plan is cleared, the pump is started, the TBR boluses are planned.
Field test: with a 0 % TBR running, raise the target or eat a little so the loop asks for > 100 %. In the log:
"applyAPSRequest: setTempBasalAbsolute()" with rate > basal, then START sent and "normal state", then TBR boluses.

## B14 — `mergeExtraOperations` drops every SUSPEND after the first delayed REACTIVATE (more insulin than asked)
Priority: P1 (can deliver more insulin than the TBR asked)
Seen: code review during the analysis of `logs/temp_basal_stuck.log`, 2026-11-16. **Not proven in the field. A test
must confirm it first.**
What happens (from reading the code, `MedLinkMedtronicPumpPlugin.kt:2518-2545`):
- When `deltaMod > 5`, the first REACTIVATE is moved 5 min later, and `previousReleaseTime` is set to its new time.
- `previousReleaseTime` is never reset. So **every** later SUSPEND (all have a later time) is dropped
  (`toRemove` is filled but never used; the SUSPEND is just not added). The REACTIVATEs after them stay, so the
  plan can end with REACTIVATE, REACTIVATE, … The rest of the TBR then runs at 100 %, not at the asked rate.
- `mod` given to it (`:2515`) is the leftover of `operationDuration` (the variable is reused at `:2432`), not of
  `operationInterval`. Check that this is the value that was meant.
- Example from the log: 0.32 U/h for 30 min with basal 0.75 → 17 min suspended asked. Plan: 2 suspensions, one
  merged, `mod = 3`, `deltaMod = 6` → the first REACTIVATE moves 5 min and the second SUSPEND is dropped. Check how
  many suspended minutes are left in the end. Fewer than 17 means more insulin than asked.
- Also confusing for log readers: every step logs `duration = totalSuspendedMinutes` (17 at line 16870), not its
  own length.
What should happen: the plan suspends for the asked minutes (rounded down to never give more insulin), every
SUSPEND has its own REACTIVATE, and no REACTIVATE comes twice in a row.
Where: `MedLinkMedtronicPumpPlugin.kt:2398` (`buildSuspensionScheduler`), `:2518` (`mergeExtraOperations`).
Tests needed (first move the plan math to a pure, testable class like the other `MedLink*Rule` classes):
- for each rate 0 %…95 % in steps of 5 % and durations 30 / 60 / 120 min: suspended minutes ≥ asked minutes (rounded
  to 5 min), steps alternate SUSPEND / REACTIVATE, the first step is a SUSPEND, the last is a REACTIVATE at or before the end.
- the case from the log: 30 min, 17 min suspended, start "active for 4 min".
Field test: a 40 % TBR for 60 min. Count the minutes between each "suspend state" and the next "normal state"
in the log. The sum must be about 36 min, not less.

## B15 — No alarm when the pump stays suspended longer than the plan

**Status: done with B12 (`MedLinkResumeRule`, alarm "Pump still suspended"). Not done: a second guard outside the app process (`AlarmManager`). Field test needed.**
Priority: P1 (insulin stopped with no alarm)
Seen: field, night of 2026-11-16 (B12). The pump was suspended for about 4 hours and nobody was told.
What happens: on the Veo, a suspend through MedLink has no end time. Only the phone starts the pump again. If the
app dies, freezes, loses BLE, or the plan loses its REACTIVATE (B12, B14), the pump stays suspended with no limit.
T20 (`backlog/working/tasks-002.md`) alarms only after failed connections. T13 alarms only when a start
fails. Neither covers "no start was ever tried".
What should happen: an alarm (sound, and it works with the screen off) when the pump is suspended and either:
(a) the TBR plan has no REACTIVATE step left, or (b) the last suspend is older than the planned end + 10 min
(check the margin with the user), or (c) the pump is suspended and no TBR / loop decision is running. Also a
second guard that does not depend on the app process (for example an Android `AlarmManager` alarm set when the
pump is stopped and removed when it is started), so a killed or frozen app still alerts the user.
Where: TBR plan in `MedLinkMedtronicPumpPlugin.kt` (`scheduleSuspension` `:2547`, `refreshAnyStatusThatNeedsToBeRefreshed`
around `:1136`), `stopPump` / `startPump`. Reuse the alarm work of T20 / T13 if it exists. Do not make a second alarm system.
Tests needed: suspended + empty plan → alarm; suspended + plan ends 01:56 + now 02:07 → alarm; started on time → no
alarm; the alarm is cleared when "normal state" is read.
Field test: start a 0 % TBR for 30 min, then force-stop AAPS. An alarm must sound about 10 min after 30 min. Then
start AAPS: the pump must be started (or the user is asked to start it).
