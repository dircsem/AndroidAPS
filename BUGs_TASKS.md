# MedLink bolus / stop reliability — task backlog

Source: `BUGs.md`. Tasks written from a PO perspective, grounded in the current code.

## Pump behaviour (facts this plan relies on)

- **`M` (`BolusStatus`)** returns the last bolus (amount, time) and the delivered amount, and reports when a bolus is still being delivered.
- **`A` (`StopStartPump`)** answers with the pump state, including `pump bolusing state`.
- **If a stop is issued while the pump is bolusing, the pump aborts the bolus and suspends.** The bolus is only partially delivered.

So a stop sent during a bolus does **not** wait — it cuts the bolus short. What to do depends on who issued the stop:

- **User-issued suspend** → send it immediately, even if the pump is bolusing. Interrupting the bolus is intended. Afterwards, read `M` and record the delivered amount.
- **Automatic stop** (temp-basal suspension, scheduled suspension, status repair) → never interrupt a bolus. Check `M` first and wait for the bolus to finish.

If an automatic stop interrupts a bolus anyway (e.g. after a BLE drop), read `M` and record the partially delivered amount.

Today every MedLink stop is automatic: `MedLinkMedtronicPumpPlugin.kt` calls `stopPump()` from the temp-basal suspend schedule (`:1034`), `setTempBasalAbsolute` (`:1832`), `scheduleSuspension` (`:2234`) and `handlePumpStatus` (`:4158`). The generic `CommandStopPump` only acts on `Insight` pumps, so there is no user-suspend path for MedLink yet.

## Root causes found in code

1. **"Bolusing" is recorded as "Suspended".** `BleStopCommand.kt:39` sets `lastStatus = Suspended` when `A` answers `pump bolusing state`. The queue then drops a queued stop as "already done" (`MedLinkBLE.kt:1417`, `:1434-1441`), so the app's view of pump state diverges from reality.
2. **After a disconnect the stop runs without checking `M`.** `close()` moves an unfinished bolus into `onHoldCommandQueue` (`MedLinkBLE.kt:1280-1286`), which the next-command picker (`MedLinkBLE.kt:1419-1433`) never reads. A queued stop then runs immediately — if the pump was mid-bolus, the stop aborts it.
3. **The held bolus is only checked if some later `M` call happens.** Nothing schedules an `M` after reconnect.
4. **`M` answers are compared too loosely.** `BleBolusStatusCommand.kt:45` treats the held bolus as delivered when `insulin == lastBolusAmount`, ignoring the bolus time and delivered amount. An older bolus with the same dose is taken as this one → new bolus dropped.
5. **Bolus re-sent after a connection drop without checking `M`.** On disconnect after the pump confirmed, the bolus is cleared and re-sent (`MedLinkBLE.kt:1832-1835`); `close()`/`retryCommand()` and `reprocessOnHold()` can also run it again. `BleBolusCommand.kt:51` re-sends whenever the last bolus is > 0.2 U, without comparing its time to this request → possible double bolus. Also, a pump confirmation does not guarantee the bolus was applied, and today nothing verifies it with `M` afterwards. Rule going forward: every bolus is verified with `M`; it is re-sent only when `M` shows it was not started, with guards so the applied amount is never duplicated (T7).
6. **Sleeps on the BLE callback thread.** 15 s (`BleStopCommand.kt:40`), 180 s on GATT 133 (`MedLinkBLE.kt:1846`), 5 s between small boluses (`MedLinkBLE.kt:1578`). They block BLE handling and add to disconnects.
7. **`delivered == 0` is treated as "bolus completed".** `BolusProgressCallback.kt:62` records the bolus and dismisses progress when `bolusDeliveredAmount == 0.0`. A bolus that was never applied therefore gets recorded as delivered (IOB too high → loop under-doses), and nothing re-checks it.
8. **The held-bolus queue is invisible to the queue.** `hasCommandsToExecute()` (`MedLinkBLE.kt:180`) does not count `onHoldCommandQueue`, so `nextCommand` returns `null` before ever reaching the on-hold branch when the other queues are empty.
9. **Start (resume) can be dropped as "already done".** Same skip rule as (1): `startCommandStatusMatch` drops a queued `StartPump` when `lastStatus == Running` in the last 60 s, and `BleStartCommand.kt:30` sets `Running` on `pump is bolusing st`. `startPump()` (`MedLinkMedtronicPumpPlugin.kt:447`) also returns without sending if the app-side `pumpRunningState` is (stale) `Running`, and `removeFirstCommand` (`MedLinkBLE.kt:152`) deletes **every** queued `StartPump` when one start executor is removed. Nothing reads `A` after a start to verify the pump is really running. Likely cause of "0% temp basal: reactivation sometimes doesn't work" (T13).

## Backlog

**Epic: pump answers (`M` / `A`) decide every bolus and stop action**

### P0 — safety

#### T0. Tag every stop with its origin; add a user suspend for MedLink
- Add an origin to stop commands (`USER` / `AUTOMATIC`), carried from the plugin down to `BleStopCommand` and the MedLink queue.
- The four existing `stopPump()` calls in `MedLinkMedtronicPumpPlugin.kt` are `AUTOMATIC`.
- Add a user suspend action for MedLink (e.g. make `CommandStopPump` / a MedLink custom action reach the plugin) tagged `USER`.
- A `USER` stop goes to the priority queue: it overtakes queued boluses and drops queued (not yet sent) loop boluses.
- **Acceptance:** user taps suspend during a bolus → stop sent right away, pump suspended, bolus aborted.

#### T1. Never let an automatic stop interrupt a bolus
- Applies to `AUTOMATIC` stops only (see T0); `USER` stops skip this check.
- Before an automatic `StopStartPump` → `StopPump`, if a bolus was sent in the last few minutes or is held/unresolved, run `M` first.
- If `M` shows delivery in progress, do not send the stop. Schedule it (on the handler, no sleep) for when the remaining dose should be done: `(lastBolusAmount − bolusDeliveredAmount) × ~40 s/U` + margin, then run `M` again.
- Send the stop only after `M` shows delivered == requested.
- **Acceptance:** start a bolus, queue an automatic stop (temp suspension) → log shows `M`, wait, `M` again, then stop. Bolus fully delivered.

#### T2. Add a `Bolusing` running state; stop recording "bolusing" as "Suspended"
- Add `Bolusing` to `MedLinkPumpRunningState`. When `A` answers `pump bolusing state`, set `Bolusing`.
- The skip rule in `nextCommand` (`startCommandStatusMatch`) must only act on `Suspended`/`Running` read from the pump in the last 60 s, never on `Bolusing`.
- In `BleStopCommand`, on `pump bolusing state`: an `AUTOMATIC` stop is held and rescheduled (T1); a `USER` stop proceeds.
- **Acceptance:** unit test: `A` → bolusing, then automatic stop queued → stop is not dropped as already done.

#### T3. Record the partial bolus when a stop interrupted one
- Whenever a stop ran while the pump was bolusing — always possible for a `USER` stop, and for an `AUTOMATIC` stop after a BLE drop — immediately run `M` after the stop.
- Record `bolusDeliveredAmount` from `M` as the delivered amount for that bolus in pump sync (update the existing bolus record, not a new one).
- Do **not** automatically re-send the remainder. After a `USER` suspend, show the user the delivered vs requested amount. After an automatic stop, let the loop recalculate on its next run.
- **Acceptance:** user suspend mid-bolus → bolus record shows the partial amount from `M`; IOB uses the partial amount; no re-send.

#### T4. Resolve held boluses on every reconnect
- When a bolus is held/unresolved, put a high-priority `M` at the front of the queue on reconnect, ahead of any stop/start.
- Make the next-command picker aware of `onHoldCommandQueue` (or replace it, see T8).
- **Acceptance:** no held bolus stays unresolved past one reconnect.

#### T5. One strict interpreter for `M` answers
- One function `M answer + sent bolus → outcome`:
  - **not started** — last bolus time is before the bolus was sent
  - **delivering** — time after send, delivered < requested, pump still bolusing
  - **delivered** — time after send, delivered == requested (± 0.05 U)
  - **partial** — time after send, delivered < requested, pump no longer bolusing (e.g. aborted by stop)
  - **unknown** — answer missing or cannot be parsed
- `BleBolusStatusCommand` and `BleBolusCommand` use it instead of the `==` and `> 0.2` checks.
- **Acceptance:** unit tests with real `M` answers for each outcome, including same dose with an older time → not started.

#### T6. Loop bolus ending as unknown → verify with `M`
- When an SMB / temp-basal bolus ends without a clear result (BLE drop, timeout, no confirmation), queue `M` and decide using T5:
  - **delivered** → record it once in pump sync
  - **partial** → record the delivered amount
  - **delivering** → wait and run `M` again
  - **not started** → re-send only through the T7 flow (fresh `M`, retry limit, not stale); otherwise mark failed and let the loop recalculate
  - **unknown** → retry `M` (see T8); never re-send on unknown (see T7)
- Block new loop boluses until the previous one is resolved.
- **Acceptance:** replay tests for each outcome; no double bolus; no delivered bolus missing from IOB.

#### T7. Re-send a bolus only after `M` proves it was not applied — never duplicate
**Rules:**
1. A pump confirmation of the bolus command does **not** mean the bolus was applied (observed in the field). Every bolus, confirmed or not, dropped connection or not, is verified with `M` before it is considered done.
2. A bolus is never re-sent blindly (on a connection drop, a timeout or an unclear answer). It may be re-sent only when `M` shows it was **not started**.
3. The insulin applied for one bolus request must never be counted or delivered twice.

**Verify after every bolus**
- Before writing the bolus command, take a **baseline** `M` read (last bolus time and amount), or reuse one from the last few seconds.
- After the command (confirmed or not), wait for the expected delivery time (`amount × ~40 s/U` + margin) and read `M`.
- **Applied** = `M` shows a last bolus newer than the baseline and newer than the send time. Use T5 to get delivered / partial / delivering / not started / unknown.

**Guards against duplication**
- **One bolus in flight.** No other bolus (loop or manual) is sent until the current one is resolved.
- **Fresh `M` right before any re-send.** On the same connection, immediately before re-sending, read `M` again. If it now shows a bolus newer than the baseline, do not re-send; record it.
- **Each pump bolus is recorded once.** Record in pump sync keyed by the pump's bolus timestamp; a second sighting of the same pump bolus updates the existing record, never adds one.
- **Total never exceeds the request.** Sum of amounts applied across attempts for one request ≤ requested amount. **Partial** → record what was delivered, do not re-send the remainder.
- **Retry limit.** At most 1 re-send per request (configurable). After that, raise the T8 alarm instead.
- **Stale loop boluses are not re-sent.** If a loop bolus is older than the loop interval (~5 min) when `M` says not started, mark it failed; the loop recalculates with fresh data.
- **Manual boluses:** re-send only with user confirmation, showing what `M` reported.

**Remove the current blind re-send paths**
  - `onConnectionStateChange` clearing the confirmed bolus so it runs again (`MedLinkBLE.kt:1832-1835`).
  - `close()` / `retryCommand()` putting the bolus back to run (`clearExecutedCommand` on an unfinished bolus).
  - `reprocessOnHold()` re-running a held bolus (`BleBolusStatusCommand.kt` "reprocess command" branch).
  - `resetBolusCommand()` on `pump is bolusing` and on the `> 0.2 U` "last bolus" check (`BleBolusCommand.kt:29`, `:51`).
- Replace them with: mark the bolus **unresolved** → queue `M` → act on the T5 outcome:
  - **delivered** / **partial** → record the delivered amount once; no re-send
  - **delivering** → wait, run `M` again
  - **not started** → re-send allowed, subject to all guards above (fresh `M`, retry limit, not stale, user confirmation for manual)
  - **unknown** → keep retrying `M` (T8 alarm); never re-send on unknown
- The `reDeliverBolus` path on `pump suspend state` (`BleBolusCommand.kt:33-38`) goes through the same verify-then-re-send flow.

**Acceptance**
- Pump confirms the bolus but `M` shows no new bolus → exactly one re-send, then `M` shows it applied, recorded once.
- Pump confirms and `M` shows it applied → no re-send.
- Connection drops at each point (before write, after write, after confirmation, mid-delivery) → `M` read every time; at most one bolus applied per request.
- Same dose given twice in a row (two SMBs of 0.3 U) → both recorded, neither confused with the other (baseline + timestamp).
- Covered by T10 replay tests.

### P1

#### T8. Alarm when a bolus stays unknown
- If `M` cannot be read, or is still **unknown** after N tries / T minutes, alert the user, record the last delivered amount `M` showed, and keep new boluses blocked until resolved.
- **Acceptance:** user sees an alert; IOB is never silently double-counted or missed.

#### T9. Track each bolus in one state
- Replace `isCommandConfirmed`, `bolusCommandToBeChecked`, `needToCheckOnHold`, `onHoldCommandQueue` with a per-bolus state:
  `queued → sent → confirmed → delivering → delivered | partial | failed | unknown`.
- Only `M` / `A` answers move a bolus out of **confirmed**. Stop/start decisions read only this state plus the latest pump answer.

#### T10. Replay tests with recorded pump answers
- Fake MedLink transport that replays recorded `M` and `A` answers (incl. `pump bolusing state`, partial `M`).
- Cases: disconnect before confirmation, after confirmation, mid-delivery; user suspend mid-bolus (sent, pump aborts, partial recorded); automatic stop mid-bolus (held until done); two SMBs with the same dose back to back; loop bolus ending unknown.
- Runs in CI; covers T1–T7. **Start this alongside the P0 work.**

#### T13. Resume after a 0% temp basal must be verified
- A `StartPump` is never dropped because of a cached state; only an `A` answer read **after** the last stop was sent may skip it.
- After every start, read the state from the `A` answer (or a follow-up `A`) and retry the start if the pump is still suspended; alarm after N failed tries.
- **Acceptance:** 0% temp basal ends → log shows start sent and the pump reported running; if the pump stays suspended the user is alerted.

### P2

#### T11. Remove sleeps from the BLE callback thread
- Replace the 15 s, 180 s and 5 s sleeps with scheduled retries on the handler.
- **Acceptance:** no `SystemClock.sleep` / `Thread.sleep` on the GATT callback thread.

#### T12. Log each bolus step
- One log line per bolus state change: bolus id, requested and delivered amounts, raw `M`/`A` answer, timestamps.
- **Acceptance:** any field report can be diagnosed from logs alone.

## Suggested order

1. T10 + T12, and capture real `M` / `A` answers from the pump (normal, bolusing, partial, aborted by stop).
2. T2, T5 — small, contained, biggest safety effect.
3. T0, T1, T3, T4 — stop origin and stop/bolus interaction.
4. T6, T7 — unknown loop bolus and re-send rules.
5. T8, T9.
6. T11.

---

# Implementation plan

The rule from `DEV.md` applies to every task: **write the unit tests first, see them fail, then change the code.** The main concern is never over-delivering insulin, so whenever the plan has to choose, it chooses "don't send / record less / alarm" over "send again".

## Testing approach (shared by all tasks)

`MedLinkBLE` is tied to `BluetoothGatt`, handlers and threads, so it is hard to unit test. The plan moves every **decision** out of it into small pure-Kotlin classes that take pump answers and a clock and return a decision. `MedLinkBLE` and the `Ble*Command` classes only carry out those decisions.

- New package `app.aaps.pump.common.hw.medlink.bolus` in `pump/rileylink` (no Android imports):
  - `BolusStatusSnapshot` — one parsed `M` answer: `lastBolusAmount`, `lastBolusTime`, `deliveredAmount`, `pumpBolusing`, `readAt`, `raw`.
  - `BolusOutcome` + `BolusStatusInterpreter` (T5).
  - `BolusRequest` + `BolusTracker` (T7/T9) — the one bolus in flight and its state.
  - `StopGate` (T1) — decides if an automatic stop may be sent now.
  - `Clock` interface (`now()`), with a fake in tests.
- Tests go in `pump/rileylink/src/test/kotlin/app/aaps/pump/common/hw/medlink/...`, JUnit 5 + mockito-kotlin from `:shared:tests` (already a `testImplementation` of `pump/rileylink`).
- Recorded pump answers go in `pump/rileylink/src/test/resources/medlink/` (`m_idle.txt`, `m_bolusing.txt`, `m_partial_aborted.txt`, `a_bolusing.txt`, `a_suspended.txt`, `a_normal.txt`, …). **Open item:** we need real captures from the pump. In particular, the exact `M` line that says "still bolusing" is not in the parser yet. `parseBolusInfo` only reads `last bolus` and `square bolus: X delivered: Y`.
- `Ble*Command.characteristicChanged` tests use a mocked `MedLinkBLE` and check which method gets called (`completedCommand`, `retryCommand`, the new `holdStop`, …). They feed the answer strings exactly as the BLE layer splits them.

## T10 + T12 — test harness and bolus logging (do first)

**Tests first**
- `MedLinkStatusParserTest`: every fixture parses into the expected `BolusStatusSnapshot`. Cover: the time with minute resolution, `0.0u` square bolus, and the missing `delivered` field.
- `FakeMedLinkTransport` (test-only): a script of `(command → answer lines | DISCONNECT)` steps. Later tasks use it to drive `BolusTracker` + `StopGate`, and for the replay cases listed in T10.

**Changes**
- `MedLinkStatusParser`: add `parseBolusSnapshot(lines, readAt): BolusStatusSnapshot?`. It returns `null` when `last bolus` is missing, so callers get **unknown** and never a default of `0.0`. The two existing `parseBolusInfo` overloads delegate to it.
- `BolusTracker` logs every transition on `LTag.PUMPBTCOMM` with prefix `BOLUS[id]`: `state old→new`, requested, delivered, attempt, raw `M`/`A` answer. That covers T12. Log lines from the `Ble*Command` classes about bolus/stop also carry the request id.

## T2 — `Bolusing` running state

**Tests first** (`BleStopCommandTest`, `BleStartCommandTest`, `NextCommandSkipRuleTest`)
- `A` answer `pump bolusing state` → `setLastPumpStatus(Bolusing)`, never `Suspended`.
- `BleStartCommand` on `pump is bolusing st` → `Bolusing`, not `Running`.
- Skip rule: `lastStatus=Bolusing` with an automatic stop queued → the stop is **not** removed.

**Changes**
- `MedLinkPumpRunningState`: add `Bolusing`.
- `BleStopCommand` (`:41-46`): set `Bolusing`, remove `SystemClock.sleep(15000)` + `retryCommand()`, and branch on the stop origin (T0): `AUTOMATIC` → `bleComm.holdStop(StopGate.recheckDelay(...))` (T1); `USER` → wait for the suspend answer, then trigger T3.
- `MedLinkBLE.startCommandStatusMatch` becomes a small pure function `shouldSkipStartStop(command, lastStatus, lastStatusSet, lastStopOrStartSent, now)`. It skips only when the state was read from the pump **after** the last start/stop was sent and is less than 60 s old, and never when the state is `Bolusing`/`Unknow`. `setLastPumpStatus` already stores the time; add `lastStartStopSent`, set when a `StopStartPump` executor is posted.

## T5 — strict `M` interpreter

**Tests first** (`BolusStatusInterpreterTest`, table-driven, one row per outcome)
- not started: snapshot `lastBolusTime` ≤ baseline time (same dose, older time → **not started**, the bug in root cause 4).
- delivering: newer than baseline, delivered < requested, `pumpBolusing=true`.
- delivered: newer, `|delivered − requested| ≤ 0.05`.
- partial: newer, delivered < requested − 0.05, not bolusing.
- unknown: `null` snapshot, or a newer bolus whose amount ≠ requested (someone bolused on the pump itself; we don't guess).
- Clock skew: snapshot time within the pump-clock tolerance of `sentAt` is judged against the **baseline**, not the phone clock.

**Changes**
- `BolusStatusInterpreter.interpret(snapshot: BolusStatusSnapshot?, request: BolusRequest): BolusOutcome`.
  - "Newer" means `snapshot.lastBolusTime > request.baseline.lastBolusTime`. If there is no baseline, it falls back to `sentAt − pumpClockOffset − 60 s`. The pump clock is not the phone clock, so the baseline is the main reference.
  - The pump time has minute resolution, so two equal SMBs inside the same minute cannot be told apart. `BolusTracker` therefore does not send the next bolus until the pump minute has changed since the previous one (this replaces the 5 s sleep at `MedLinkBLE.kt:1581`).
- `BleBolusStatusCommand` (`:48`) and `BleBolusCommand` (`:52`) drop their `==` / `> 0.2` checks and pass the snapshot to `BolusTracker.onStatus(snapshot)`.
- `BolusProgressCallback` (`:62`): remove `|| bolusDeliveredAmount == 0.0` (root cause 7). Progress UI and recording are driven by the tracker outcome. **delivered**/**partial** record the bolus; **not started**/**unknown** never record it.

## T9 — one state per bolus (built together with T5, used by T1/T3/T4/T6/T7)

Kept here because T1–T7 all depend on it. It is the cleanest way to get rid of the scattered flags.

**Tests first** (`BolusTrackerTest`, fake clock)
- Allowed transitions only: `QUEUED → SENT → CONFIRMED → DELIVERING → DELIVERED | PARTIAL | FAILED | UNKNOWN` (plus `SENT/CONFIRMED → UNRESOLVED` on disconnect). An illegal transition throws in tests and logs + alarms in release.
- Only `onStatus(snapshot)` / `onPumpState(A)` move a request out of `CONFIRMED`.
- `canSendNewBolus()` is false while a request is not in a final state.

**Changes**
- `BolusRequest(id, detailedBolusInfo, origin: LOOP|MANUAL, requested, createdAt, sentAt, baseline, attempts, appliedTotal, state, lastSnapshot)`.
- `BolusTracker` (singleton owned by `MedLinkBLE`, injected with `Clock`, `AAPSLogger`, and a `BolusRecorder` interface that the plugin implements):
  - `onQueued`, `onWritten`, `onConfirmed`, `onDisconnect`, `onStatus(snapshot)`, `onPumpState(state)`
  - each returns a `BolusAction`: `None | ReadStatusAt(time) | Resend | Record(amount, pumpTime) | Fail(reason) | Alarm(reason)`.
- `MedLinkBLE`: delete `isCommandConfirmed` use for boluses, `bolusCommandToBeChecked`, `needToCheckOnHold`, `onHoldCommandQueue`, `reprocessOnHold`. `setConfirmedCommand` calls `tracker.onConfirmed()`. `MedLinkBLE` runs the returned action:
  - `ReadStatusAt` → enqueue a `BolusStatus` executor at that time (`handler.postDelayed`)
  - `Resend` → enqueue the bolus again
  - `Record` → `BolusRecorder`
  - `Alarm` → T8

## T7 — re-send only when `M` proves "not started"

**Tests first** (`BolusTrackerResendTest` + replays through `FakeMedLinkTransport`)
- confirmed + `M` new bolus = requested → `Record`, **0** re-sends.
- confirmed + `M` no new bolus → one fresh `M` → still not started → exactly **1** `Resend`. The next `M` shows the bolus → `Record` once.
- After `attempts == maxResend (1)` and still not started → `Alarm`, no further `Resend`.
- disconnect before write / after write / after confirm / mid-delivery → each case produces `ReadStatusAt`, never `Resend` directly.
- loop bolus older than 5 min when "not started" → `Fail("stale")`, no `Resend`.
- manual bolus "not started" → `AskUser` (UI confirmation), no automatic `Resend`.
- partial 0.3 of 0.5 → `Record(0.3)`, no remainder re-send; `appliedTotal ≤ requested` is checked on every path.
- two 0.3 U SMBs back to back → two records with different pump timestamps.

**Changes**
- Baseline: `deliverBolus` (`MedLinkMedtronicPumpPlugin.kt` ~`:3255` and ~`:3576`) always adds a `BolusStatus` executor **before** the bolus command, now also for ≤ 0.2 U. Its answer is stored as `request.baseline`. A snapshot younger than ~30 s can be reused.
- Verify: after the bolus command, always queue an `M` at `sentAt + requested × 40 s/U + 15 s` (`bolusMessage` is no longer `null` for ≤ 0.2 U).
- Remove the blind paths:
  - `MedLinkBLE.onConnectionStateChange` `:1835-1838` (`clearExecutedCommand` on a confirmed action command): for a bolus → `tracker.onDisconnect()`; for other action commands, keep today's behaviour.
  - `MedLinkBLE.close()` `:1280-1286` and the `clearExecutedCommand` in its "remaining commands" branches: a bolus executor is **removed** from the queues and handed to `tracker.onDisconnect()`; it is never cleared back to position 0.
  - `BleBolusStatusCommand` `:52-56` "reprocess command" → gone (the tracker decides).
  - `BleBolusStatusCommand` `:62-66` (`clearExecutedCommand` while partially delivered) → becomes `ReadStatusAt` from the tracker.
  - `BleBolusCommand` `:28-30` (`pump is bolusing` → `resetBolusCommand`): a bolus is already running, so the tracker reads `M` once it is done; no re-send.
  - `BleBolusCommand` `:45-55` (`last bolus > 0.2`) → passes the snapshot to the tracker.
  - `BleBolusCommand` `:32-40` (`pump suspend state` → `reDeliverBolus`): mark `UNRESOLVED`, queue start, then `M`. A re-send only happens through the tracker. `reDeliverBolus` in `MedLinkPumpPluginAbstract.kt:463` is reduced to "enqueue via tracker".
  - `BleCommand.kt:92-93` (`partialBolus` → `reExecuteCommand`) → `tracker.onStatus`.
  - `CommandExecutor` for a bolus: `nrRetries`/`MAX_TRIES` retry loops (`retryCommand`, `completedCommand`) must not re-post a bolus whose write was already sent. A bolus executor gets `isBolus = true`, and `retryCommand` hands it to the tracker instead.
- Recording once: `BolusRecorder.record(request, amount, pumpTime)` calls `pumpSync.syncBolusWithPumpId(timestamp = pumpTime, amount, type, pumpId = pumpTime, pumpType, serial)`. That call is an upsert keyed by the pump id, so a second sighting updates the record and never adds one. The `addBolusWithTempId` path at `MedLinkPumpPluginAbstract.kt:409` / `handleNewTreatmentData` must reuse the same `pumpId` so history reads don't duplicate it.

## T5/T7 safety net — T6 loop bolus ends as unknown

**Tests first** (`LoopBolusUnknownTest`): for each outcome the result posted to the queue command (`MedLinkCommandSMBBolus`) and the pump-sync calls match the table in T6. While unresolved, a second SMB request → rejected with `success=false, comment="previous bolus unresolved"`.

**Changes**
- `MedLinkCommandSMBBolus` / `MedLinkPumpPluginAbstract.deliverTreatment`: if `!tracker.canSendNewBolus()`, return `PumpEnactResult(success=false, enacted=false)` right away. The loop then recalculates next cycle with IOB that includes anything already recorded.
- The callback result of a loop bolus comes from the tracker's final state, not from the `set bolus` confirmation. `delivered`/`partial` → success with the delivered amount. `failed`/`unknown` → not enacted.

## T0 — stop origin + user suspend

**Tests first**
- `MedLinkBLEQueueTest` (on the extracted `CommandQueues` class, see note): a `USER` stop goes to the head of the priority queue and removes **unsent** loop boluses. Manual boluses are removed too and the user is notified. A bolus already in `SENT`/`CONFIRMED`/`DELIVERING` is not touched (T3 handles it).
- An `AUTOMATIC` stop is queued as today.

**Changes**
- `enum class StopOrigin { USER, AUTOMATIC }` in `core/interfaces/.../pump` (needed by `MedLinkPumpPluginBase`).
- `BleStopCommand(..., val origin: StopOrigin)`. `MedLinkMedtronicPumpPlugin.stopPump(callback, origin = AUTOMATIC)`; the four existing callers (`:1034`, `:1832`, `:2234`, `:4158`) stay `AUTOMATIC` by default.
- User path: add `fun userSuspend(callback)` to `MedLinkPumpPluginBase`. `CommandStopPump.execute` (`implementation/.../CommandStopPump.kt:33`) gets a `pump is MedLinkPumpPluginBase` branch, and `ActionsFragment` shows the suspend action for MedLink. Alternative with less change to the shared core: a MedLink `CustomAction` "Suspend pump" in the plugin's custom actions.
- `MedLinkBLE.addWriteCharacteristic`: `USER` stop → `priorityExecutionCommandQueue.addFirst`, and `bolusExecutionQueue.removeIf { not yet written }` (tracker state `QUEUED`).
- Note: `addWriteCharacteristic` / `nextCommand` / `removeFirstCommand` are tangled. Extract the four deques + `bolusExecutionHash` into a `CommandQueues` class with `pickNext(state)`, `enqueue(executor, priority, origin)`, `remove(executor)`. That class is unit-testable. `MedLinkBLE` keeps the GATT side.

## T1 — automatic stop never interrupts a bolus

**Tests first** (`StopGateTest`, fake clock + tracker states)
- no bolus in flight and last `A` not bolusing → `Send`.
- bolus `SENT/CONFIRMED/DELIVERING/UNRESOLVED`, or one sent < 5 min ago → `ReadStatusFirst`.
- after `M` = delivering (0.6 of 1.0) → `DeferUntil(now + 0.4 × 40 s + 15 s)`.
- after `M` = delivered → `Send`.
- `M` unknown 3× → no stop, `Alarm`. Basal keeps running (safe side), which is better than aborting a bolus.
- `USER` origin → always `Send`.

**Changes**
- `StopGate.decide(origin, tracker, lastPumpState, now): StopDecision`.
- `MedLinkBLE.nextCommand`: when the picked executor is `StopStartPump/StopPump` with origin `AUTOMATIC`, ask `StopGate`.
  - `ReadStatusFirst` → put a `BolusStatus` in front of it.
  - `DeferUntil` → leave the stop queued, pick the next command that is neither a stop nor a bolus, and `handler.postDelayed({ nextCommand() }, delay)`.
- `BleStopCommand` on `pump bolusing state` with `AUTOMATIC` → `bleComm.holdStop(...)`, which does the same deferral (no sleep).

## T3 — record the partial bolus after a stop

**Tests first** (`StopInterruptsBolusTest` replay): user stop sent while `DELIVERING` → `A` suspended → `M` shows 0.4 of 1.0 → `Record(0.4)` updates the **same** record (same pump timestamp), no `Resend`, UI event with requested/delivered. The automatic-stop-after-BLE-drop replay gives the same result without the UI event.

**Changes**
- `BleStopCommand`, on success when the tracker has a non-final bolus or the previous `A` said `Bolusing`: enqueue an `M` right after the stop (priority queue head) and mark the request `UNRESOLVED`.
- `BolusTracker.onStatus` after a stop: `partial` → `Record(delivered)` and final state `PARTIAL`. `T7` guarantees no remainder re-send.
- `USER` origin: `rxBus.send(EventDismissBolusProgressIfRunning)` + a notification with "Delivered X of Y U".

## T4 — resolve held boluses on reconnect

**Tests first**: replay "disconnect after confirmation, reconnect with an empty queue" → the first command after `Connect` is `M`, before any stop/start. Root cause 8: with all queues empty, `M` must still be scheduled.

**Changes**
- On `onServicesDiscovered`/connect-ready: if `tracker.hasUnresolved()` → `CommandQueues.enqueueFirstAfterConnect(BolusStatus)`. `hasCommandsToExecute()` also returns true when the tracker has an unresolved bolus, so `nextCommand` doesn't bail out.
- `onHoldCommandQueue` is removed (T9), which also removes the bug where the picker returns a held bolus.

## T8 — alarm when a bolus stays unknown

**Tests first**: tracker gets `unknown` N=3 times or stays unresolved for T=10 min → `Alarm` once (no repeats every cycle), `canSendNewBolus()` stays false; a later `delivered` clears it and records once.

**Changes**
- Plugin implements the alarm with `uiInteraction.runAlarm(...)` + a persistent `Notification` ("MedLink: bolus X U unconfirmed — check pump history"). Add strings in `core/ui/.../strings.xml`. Show the last `M` values.
- Custom action "Resolve bolus manually" (user enters what the pump history shows) to unblock in the field.

## T13 — verify resume after 0% temp basal

**Tests first**
- `shouldSkipStartStop`: `lastStatus=Running` read **before** the stop was sent → start not skipped; `Bolusing` → not skipped.
- `removeFirstCommand` of one start executor does not delete other queued starts (`CommandQueues` test).
- `BleStartCommand` on `pump suspend state` after the start was sent → `retryCommand` (not `completedCommand`); after 3 tries → `Alarm`.
- `startPump()` with app state `Running` but last `A` older than the last stop → still enqueues the start.

**Changes**
- `startPump()` guard (`:447`): use `medLinkPumpStatus.pumpRunningState` only if it came from an `A` answer newer than the last stop. Otherwise send the start.
- `MedLinkBLE.removeFirstCommand` `:152-154`: drop the `removeIf { contains(StartPump) }`, or limit it to duplicate executors with no callback.
- `BleStartCommand` `:52-55`: on `pump suspend state` the start was not applied (pump showing a message, see `BUGs.md`) → `clearExecutedCommand` + `retryCommand` with a bounded count. When exhausted → alarm "Pump still suspended — resume manually".
- Resuming is not an over-delivery risk, so retrying here is allowed. The bolus rules don't apply.

## T11 — no sleeps on the BLE thread

**Tests first**: a static check test (or detekt rule) that fails if `SystemClock.sleep`/`Thread.sleep` appears in `hw/medlink/ble/**`. Behaviour tests from T1/T5 already cover the delays.

**Changes**
- `BleStopCommand:43` → removed (T1/T2).
- `MedLinkBLE:1844` (180 s on GATT 133) → `handler.postDelayed({ close(true) }, backoff)` with exponential backoff (5 s, 15 s, 60 s, 180 s), and `MedLinkConnectionError` broadcast kept.
- `MedLinkBLE:1854` (500 ms) → removed.
- `MedLinkBLE:1581` (5 s between small boluses) → the tracker's "pump minute changed" rule (T5) with `postDelayed`.
- `BleCommand.kt:179` (700 ms), `MedLinkBLE.kt:618` and `:974` → `postDelayed`.
- `BolusProgressCallback` `SystemClock.sleep(200/1000)` → `handler.postDelayed`.

## Order of work (matches "Suggested order")

1. T10 harness + fixtures + `parseBolusSnapshot` + T12 logging (needs real `M`/`A` captures from the pump).
2. T2, T5 (+ remove root cause 7). These are small, and the tests for them can be written today.
3. T9 tracker skeleton, then T0 (needs `CommandQueues` extraction), T1, T3, T4.
4. T7, T6.
5. T8, T13.
6. T11.

Each step is one PR. It must pass `./gradlew :pump:rileylink:testFullDebugUnitTest :pump:medtronic:testFullDebugUnitTest` and get a field test with the pump before the next blind-path removal lands.

---

# Implementation status (2026-11-07)

Pure decision layer in `pump/rileylink/.../hw/medlink/bolus/` (tests in `src/test/.../medlink/`, 100 tests):
`BolusStatusSnapshot` + `MedLinkStatusParser.parseBolusSnapshot` (chunk-safe), `BolusStatusInterpreter` (T5),
`BolusTracker` (T7/T8/T9/T12), `StopGate` (T1), `StartStopSkipRule` (T2/T13), `BolusProgressRule` (root cause 7).

| Task | Status |
|---|---|
| T0 | Done: `StopOrigin`, `BleStopCommand.origin`, `userSuspend` → `CommandStopPump` branch + MedLink custom action "Suspend pump"; USER stop to priority head, unsent boluses dropped + alarm |
| T1 | Done: `StopGate` in the command picker; `BleStopCommand` bolusing → `holdStop()` (no sleep) |
| T2 | Done: `Bolusing` state in stop/start commands; skip rule never acts on it |
| T3 | Done: `A` suspended → tracker reads `M` now → partial recorded, never re-sent |
| T4 | Done: held bolus verified by `M` on reconnect; picker no longer runs `onHoldCommandQueue` |
| T5 | Done: strict interpreter; `== lastBolusAmount` / `> 0.2` checks removed; root cause 7 fixed in `BolusProgressCallback` |
| T6 | Done: `deliverBolus` refused while a bolus is unresolved |
| T7 | Done: re-send only after 2 × NOT_STARTED with a baseline, max 1, stale loop bolus failed, manual bolus → alarm (no auto re-send); `close`/`retryCommand`/`reprocessOnHold`/`resetBolusCommand` blind paths removed; baseline `M` before each bolus; minute rule replaces the 5 s sleep |
| T8 | Done: alarm once after 3 unknown / 10 min; `resolveManually` exists in the tracker, **no UI yet** |
| T9 | Partial: tracker owns the bolus state; legacy flags (`isCommandConfirmed`, `bolusCommandToBeChecked`, `onHoldCommandQueue` as holding area) still present |
| T10 | Partial: tracker-level replay tests + synthetic fixtures; **real pump captures needed** (`src/test/resources/medlink/README.md`); no transport-level fake |
| T11 | Partial: 15 s, 180 s, 500 ms and 5 s sleeps removed; BleCommand 700 ms, BleConnectCommand 500 ms, BleCalCommand 60 s, executor pacing and `medLinkConnect` sleeps remain |
| T12 | Done for tracker transitions (`BOLUS[id]` lines) |
| T13 | Partial: skip rule uses state read after last start/stop; `removeFirstCommand` keeps other starts; `startPump` trusts Running only if confirmed; **no retry/alarm when the pump stays suspended after START** (needs captures of the START answer) |

Field test before release: every item above changes how boluses are sent/verified over BLE and was only unit tested.

### Extra bug — `M` followed by "invalid command" (2026-11-07)
- Cause: after `m command confirmed`, the `M` executor has no step left but stays at the head of the queue until "ready" arrives. Any `nextCommand()` in that window (after "time to powerdown" clears `commandQueueBusy`, a delayed `requestBolusStatus`, …) posted it again, and `run()` wrote `nextCommandData()` = **empty payload** → MedLink answered "invalid command" → `clearExecutedCommand` + `close(true)`. The `RemoveStopCommand` marker (empty code) also got queued and wrote an empty payload.
- Fix: `CommandExecutor.dataToWrite()` / `isAwaitingAnswer()` / `postDecision(current)`; the regular executor never writes an empty payload; the picker does not re-post an executor waiting for its answer and drops executors with nothing left to write (a held bolus stays with the tracker); "time to powerdown 5" with no answer to `M` → read `M` again.
- Tests: `CommandExecutorPostTest` (10), `BleBolusCommandsTest` (+2).
