Status: STARTED (2026-11-15, developer)

# Development bugs

Source of B10 / B11: the analysis of `logs/AndroidAPS.log` (2026-11-14, about 1 h, 16:25–17:25). The goal is to send
fewer commands to the pump, to save the pump battery. In that hour there were 17 MedLink connections and 96
written commands: OK+CONN 35, S 30, A 18, START 4, STOP 3, M 4, X 1, BOLUS 1. Related tasks: T23, T24, T25.

## B10 — The first S / A / M of a connection is written as an empty line and must be sent again 10 s later
Priority: P2 (it wastes pump battery and radio time; it also causes most "invalid command" answers and delays
START/STOP by 10–20 s)
Seen: log analysis, 2026-11-14.
What happens:
- In the write callback, the data of the first command after the pump wakeup is often only `0D 0A` (an empty
  line), not `53 0D 0A` ("S\r\n").
- The MedLink echoes the letter, then waits. After 10 s it says "time to powerdown", the code logs
  "Retrying 0", and writes the command again. The second write has the right data and works.
- Example (16:43):
  ```
  16:43:12.986 writing <S>            onCharacteristicWrite SUCCESS Data 0D 0A
  16:43:13.056 answer: S
  16:43:23.060 time to powerdown  →  Retrying 0
  16:43:23.148 writing <S>            onCharacteristicWrite SUCCESS Data 53 0D 0A   →  status read OK
  ```
- In the hour: **13 of 30 S**, **12 of 18 A** and 1 of 4 M writes had the empty data. The 2–3 `A` before each
  START/STOP and most of the 10 "invalid command" answers come from this.
- Cost: about 25 extra pump commands per hour, plus about 10 s more pump radio time each (about 4 min per hour).
Likely cause (not proven):
- `MedLinkBLE.kt` CommandExecutor `run()` (:684-731) sets `chara.value = payload` on the **shared**
  characteristic object, then calls `writeCharacteristic(chara)`.
- If another write sets the value to `\r\n` (an empty command) between these two steps, or the callback
  reads the value after it changed, the pump gets the wrong data.
- It looks like the old "empty payload" bug (`MEDLINK_CHANGES.md`, the `M` → "invalid command" fix with
  `CommandExecutor.dataToWrite()`), coming back on the first command after the wakeup (the Connect executor is
  removed at the same time, :711).
- Check:
  - who writes `\r\n` alone;
  - whether two writes run at the same time (`CommandExecutor` thread and `Binder` thread);
  - whether the payload is split into "S" and "\r\n".
What should happen:
- Every command is written with its full data in one write (`S\r\n`, `A\r\n`, ...).
- No empty write is ever sent.
- One write per command, with no 10 s retry when the pump is fine.
- On Android 13+ use `writeCharacteristic(characteristic, value, writeType)`, which does not use the shared
  value. On older Android, write the value only on the BLE thread, one write at a time, and wait for
  `onCharacteristicWrite` before the next write.
- Log the bytes sent (hex) on the "writing" line, so this can be checked in the next log.
Safety:
- This touches the write path of every command, including BOLUS, STOP and START.
- A retry must never send a bolus twice. The bolus tracker rules (T7: re-send only after 2 × NOT_STARTED with a
  baseline) must stay as they are.
Where: `pump/rileylink/.../medlink/ble/MedLinkBLE.kt` :684-731 (run / write), :2248-2251 (`onCharacteristicWrite`),
:1849 (the "nothing left to write" removal), `CommandExecutor.dataToWrite()`.
Tests needed (first):
- a unit test that the data written for S, A, M, START, STOP and BOLUS is never only `\r\n`;
- the Connect executor removed while the next command is written → the next command still writes its full data;
- the existing `CommandExecutorPostTest` and `NoSleepOnBolusPathTest` still pass.
Field test:
- Run 1 h with a TBR plan, then count again: `writing <S>` / `<A>` with `Data 0D 0A` should be 0, and "Retrying" /
  "invalid command" near 0.
- The number of A before each START/STOP should be 1.
- Bolus: a 0.2 U SMB and a 1 U manual bolus are each delivered once.

### Developer notes (2026-11-15) — done (different cause), needs field test
- **The "empty write" is not the cause.** There is only one write path (`MedLinkBLE.kt` run → `writeCharacteristic`), and
  it always uses the full payload (its "writing <S>" line). The MedLink **echoes the letter** ("S" at 16:43:13.056), so it
  received it. `Data 0D 0A` in the write callback is the **shared** characteristic value: the MedLink sends and receives on
  the same characteristic (`ffe1`), so the "\r\n" of the answer that came in meanwhile overwrites it.
- **Real cause (whole log, 24 cases):** the first command after a **fresh** MedLink + pump wake-up ("med-link wake up by
  bluetooth … send command for pump wakeup / confirmed pump wakeup / medtronic veo / ready"), written ~0.8 s after
  "ready", failed **12 of 12**: echoed, no pump answer, MedLink "time to powerdown" after 10 s, then sent again. After a
  "ready" of a MedLink that was already awake: 1 of 12 failed. (The 0.8 s are a `SystemClock.sleep(700)` in
  `BleCommand` on the Binder thread before `completedCommand`, plus the queue.)
- **Fix:** new `MedLinkFreshWake` (tested): `BleCommand` reports a fresh wake-up "ready" (`MedLinkBLE.onFreshWakeReady`);
  the queue holds the first command until **2 s** after it (`SETTLE_MS`, `scheduleNextCommand`), then sends it once.
  Nothing is sent twice; the bolus tracker rules are not touched. The log says "fresh MedLink wake-up: the first command
  waits 2000 ms".
- **Logs:** "writing <S> [53 0D 0A] …" now shows the bytes really sent; the write callback says "last characteristic
  value" (not always the bytes sent).
- Not changed: the `SystemClock.sleep(700)` on the Binder thread (T11 says no sleeps there; separate change).
- Tests: `MedLinkFreshWakeTest` 4/4; rileylink 536/536 (with `CommandExecutorPostTest`, `NoSleepOnBolusPathTest`).
- **Field test:** 1 h with a TBR plan. After each "fresh MedLink wake-up" line the first S / A / M must be answered (no
  "time to powerdown" + "Retrying 0" after it). If some still fail, the 2 s are not enough: send the log.

## B11 — A status read is requested every 5 seconds while a TBR plan exists
Priority: P2 (queue and phone battery churn; some of these requests become extra S reads to the pump)
Seen: log analysis, 2026-11-14: **473** "READSTATUS Scheduled Status Refresh" requests in 1 h (one every ~7 s), and
468 `CommandReadStatus executed`.
What happens:
- `onStartScheduledPumpActions` (`MedLinkMedtronicPumpPlugin.kt:680-700`) checks every 5 s.
- `doWeHaveAnyStatusNeededRefreshing` (:785-812) answers "yes" when **any** TBR plan operation has
  `releaseTime.isAfter(now − 30 s)`. That is true for every operation in the future, so it is always "yes"
  while a plan exists. Then `commandQueue.readStatus("Scheduled Status Refresh")` is added each time the queue
  has no status command.
What should happen:
- Ask for a status read only when something is **due**:
  - a refresh type whose time has come (the existing `statusRefresh` map);
  - or a plan operation with `releaseTime <= now` (plus a small margin), that is not done yet.
- Use `dateUtil.now()`.
- Not every 5 s "just in case".
- Keep the existing 10-min safety read (`lastConnection > 590000` → `readPumpData`).
Safety: the TBR plan operations (suspend / reactivate / TBR bolus) must still run on time. Check that the
operations are not triggered only by this status read. If they are, trigger them when they are due, not every 5 s.
Where: `MedLinkMedtronicPumpPlugin.kt` :680-700, :785-812, `refreshAnyStatusThatNeedsToBeRefreshed` (:1195-1240).
Tests needed (first): a rule function with:
- a plan with operations 10 min ahead → no read;
- an operation due now → read;
- an operation done → no read;
- an overdue refresh type → read.
Field test: 1 h with a TBR plan. "Scheduled Status Refresh" appears only when a plan operation or a refresh is due,
about 12–20 times per hour, not 473. Every planned STOP / START / TBR bolus still happens at its time (compare
with "Next" on the Glass skin).

### Developer notes (2026-11-15) — done, needs field test
- Checked the safety point: the TBR plan steps are started **only** inside `refreshAnyStatusThatNeedsToBeRefreshed`
  (called by every status read). That function uses the same check as a gate. So the check must still say "yes"
  when a step is due, but not for steps far in the future.
- New pure rule `MedLinkStatusReadRule.needsRead(now, refreshTimes, steps, timeChanged)` (`pump/medtronic/.../data/`):
  read when a refresh type time is `<= now`, or a step not sent yet has `releaseAt < now + 30 s` (the same 30 s
  margin the step runner uses, so a step is not late), or the time / time zone changed.
- `doWeHaveAnyStatusNeededRefreshing` now calls the rule with `dateUtil.now()`. A `null` refresh time is "not
  planned" (before: `!!` crash).
- Change in behaviour: a step that is late and not sent now also asks for a read. Before, only future steps did
  (but a future step was always there, so in practice the late step also ran). The 10-min safety read is not changed.
- Tests: `MedLinkStatusReadRuleTest` (10 tests): steps 10 min ahead → no read; due now / in < 30 s / late → read;
  sent → no read; sent first + next due → read; refresh overdue → read; null refresh → no read; time changed → read.
- Field test: 1 h with a TBR plan. "Scheduled Status Refresh" should appear only near a step or a refresh time,
  not every 5–7 s. Every STOP / START / TBR bolus still happens at its time (compare with "Next" on Glass).
- Checked (no change needed): `Configuration` / `PumpTime` are never put in the refresh list. In
  `scheduleNextRefresh` only `PumpHistory` reaches `StatusRefreshAction.Add`; the other types go to `GetData`, which
  adds nothing. So only `PumpHistory` can be "due".
- Owner rule (2026-11-15): no sensor connects to the pump any more. The sensor branch of `scheduleNextReadState`
  (`EnliteInterval`, `pumpTimeDelta`) stays as it is. Without a sensor `lastBGTimestamp` is 0 and the next read is
  last connection + 5 min (phone clock), so the pump clock does not move it.

## Field result (2026-11-17, `logs/invalid_command.log` 07:51-08:51, build 2026.11.17-07.23)
- B10: **fixed.** Every "fresh MedLink wake-up" (7 of 7) waited 2 s and the first command (`S` or `A`) was answered
  (before: 12 of 12 failed). It showed the next bug: a second OK+CONN inside STOP / START after the wake-up banner
  (B16, `bugs-004.md`).
- B11: **fixed.** 8 "Scheduled Status Refresh" in 13 min (about 37 / h, before 473 / h); most are skipped by T23
  without a connection.
