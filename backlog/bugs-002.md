Status: OPEN

# Development bugs

## B5 — Pump battery box does not show the warning colors (yellow / red)
Priority: P2
Seen: user, field, 2026-11-14 ("battery button isn't displaying the warning colors").
Skin: **Glass** (user, 2026-11-14). Battery mode (MedLink setting "battery info" = level or age) not given yet;
the fix must work in both.
Most likely cause: the Glass pump battery pill is colored by the battery **age** only (see the Glass point
below). Start there. The circle-top points stay as checks for T14.
What happens: the pump battery box on the dashboard keeps the normal color when the battery is low or old.
The reservoir and site boxes were not reported.
What should happen: the box is yellow at the warning threshold and red at the critical threshold, the same
levels as the AAPS status lights:
- level mode: `OverviewBattWarning` (default 51 %) / `OverviewBattCritical` (default 26 %);
- age mode: `OverviewBageWarning` / `OverviewBageCritical` (hours since the last `PUMP_BATTERY_CHANGE`).
Where (what the owner found, not yet proven):
- The circle-top skin takes the **text** and the **color** from two different sources:
  - The text comes from `OverviewViewModel.kt:730`: `activePump.batteryLevel` %, else the age of the last
    `PUMP_BATTERY_CHANGE`.
  - The color comes from `StatusViewModel.buildBatteryStatus()` (`ui/.../statusLights/StatusViewModel.kt:322-358`)
    and is used in `DashboardCircleTopCompose.kt:625` (`worseLevel(ageStatus, levelStatus)`).
  - If the two disagree, you see a value with no color. Check each case:
    - `hasLevel` also needs `pump.model().supportBatteryLevel`.
    - `levelStatus` is `UNSPECIFIED` when `showLevel` is false.
    - `ageStatus` is `UNSPECIFIED` when there is no `PUMP_BATTERY_CHANGE` event.
- MedLink level: `MedLinkMedtronicPumpPlugin.kt:408` `batteryLevel` = `batteryRemainingFlow`, null in age
  mode. Check that the % really goes below 51 / 26 on a weak battery: the Medtronic level is computed from
  the voltage, so a weak battery may still show a high %.
- MedLink age: the plugin keeps its own `medLinkPumpStatus.batteryAge` (`MedLinkMedtronicPumpPlugin.kt:1570`,
  `:4349`). The status lights use the `PUMP_BATTERY_CHANGE` event. Check that both give the same time.
- Refresh: `StatusViewModel` refreshes on `PumpStatus`, `TherapyChange` and `Tick` (`:128-142`). Check that a
  new MedLink battery reading sends a pump status event, so the color changes without waiting.
- **Glass skin (reported):** `StatusAgoraCard.kt:174` colors the pump battery with `batteryAgeStatus` **only**
  (`GlassOverviewComposeEmbedded.kt:498`), and the pill shows `batteryAge` as its value. In level mode it
  never turns yellow/red, and it may show an age where the user expects a %. Expected: show the level when
  the pump gives one (else the age), and color with the worse of level and age, like circle-top
  `DashboardCircleTopCompose.kt:625`.
- Glass MedLink battery pill (`StatusAgoraCard.kt:193`, `medLinkBatteryStatus` = level) should already
  color. Check it in the same field test.
- `AimiDashboardComposeEmbedded.kt:59`: `statusViewModel` is nullable. If a caller passes null, every box
  loses its colors. Check all the callers.
Tests needed: unit test for the color decision with these cases: level only, age only, both, none, values
at and around the thresholds, and a MedLink pump in level mode and in age mode. If the color logic stays
inside a Composable, move the decision to a plain function so it can be tested.
Field test: set `OverviewBattWarning` above the current pump battery % (or log a battery change with an old
date in age mode). On the Glass skin, the pump battery pill must turn yellow, then red with the critical
value. Test both battery modes. Check that the MedLink battery pill changes color too.
Related: T14 (check all the other boxes).

## B6 — Pump profile differs, but the AAPS profile is never updated (only a message)
Priority: P1 until proven otherwise (see "Safety")
Seen: user, field, 2026-11-14: "whenever there is a different profile at the pump, the AAPS profile isn't
updated; today there is only a message that they are different."
Background (agreed rules, do not change):
- MedLink **cannot write** the basal profile to the Veo. `setNewBasalProfile` stays commented out on purpose.
  So the only way to make the two equal is: **AAPS takes the pump profile.** The pump is the source of truth.
- 4v (`MEDLINK_CHANGES.md`): `isThisProfileSet` always answers "set" and only shows the notification
  `medlink_profile_differs_from_pump` (`MedLinkMedtronicPumpPlugin.kt:1811-1830`, `:1953-1981`).
- 4ah / 4al: the pump profile (E + F) is read at app start and when the basal in `S` is not the expected one
  (`readPumpProfile` :1522, `onPumpProfileRead` :1846). There are two ways to take it over, both with a
  confirm dialog:
  - the setting "Accept profile changes from the pump" (`AcceptPumpProfile`, **off by default**);
  - the "Use pump profile" button on the notification, shown **only when `lastPumpProfile != null`**.
  Both were **not field tested**.
What happens: the user only sees the "profile is different" message. The AAPS profile stays as it was.
Find out why. Check each of these, in the log and in the code:
1. The setting `AcceptPumpProfile` is off (default), so no dialog comes by itself.
2. The button is missing because `lastPumpProfile` is null: the full E + F read never finished, or failed.
   Look for `ProfileCallback` / `onPumpProfileRead` in the log. Note: the basal-only diff (from `S` /
   `basalsByHour`) can exist without a full profile.
3. The dialog (`EventShowDialog.OkCancel`) is posted while the app is in the background, or on a screen
   without `GlobalDialogHost`. Then it is lost, and it is only asked again at the next profile read (4ah limit).
   Also check that the Glass skin shows the notification's action button (the card cuts at 3 lines, 4al).
4. `askToAcceptPumpProfile` returns early: there is no AAPS profile; `differences` is empty (IC / ISF / targets
   in tolerance, but the basal differs in the other check, because the two checks use different code:
   `MedLinkProfileDiff` vs `MedLinkPumpProfileDiff`); or the hash equals the ignored one (`MedLinkIgnoredPumpProfile`).
5. Accept fails: `repository.add` or `createProfileSwitch` fails. Look for `medlink_pump_profile_switch_failed`.
6. After the switch, does the notification go away? The next `isMedLinkProfileSet` compares again.
What should happen (owner proposal; **the user must confirm the open questions first**):
- When the pump profile differs, AAPS gets the pump profile. Either it switches by itself, or it asks once
  with a dialog that cannot be lost: it is shown again when the app comes to the front, and the
  notification always has the button.
- After the switch, the message goes away and the log says which profile is active.
Safety (why P1):
- With MedLink the TBR is made of suspends and micro boluses. Check which basal those amounts are computed
  from: the AAPS profile or the pump's `basalsByHour`.
- If they use the AAPS basal while the pump runs its own basal, a profile difference changes the insulin
  delivered. That can mean **more insulin than asked**.
- The loop (IOB, SMB, ISF / IC) also uses the AAPS profile. With the pump's bolus wizard values different,
  manual boluses and SMBs disagree.
- Write the answer in this item. If there is no over-delivery path, lower it to P2.
- Taking the pump profile also changes ISF / IC / targets, so it changes how the loop doses. That is why
  the user decides between "automatic" and "ask" (open question below).
Where: `MedLinkMedtronicPumpPlugin.kt` :1522, :1811-2015; `ProfileCallback.kt`;
`MedLinkPumpProfileDiff.kt`, `MedLinkProfileDiff.kt`; `MedLinkMedtronicBooleanPreferenceKey.AcceptPumpProfile`;
`core/ui/.../GlobalDialogHost.kt`.
Tests needed (first, DEV.md):
- profile read → differences → the dialog or the switch happens;
- a full profile not read → what the user sees;
- the dialog cancelled / ignored hash → no loop of questions;
- accept → a new local profile at 100 % and the notification dismissed;
- the basal diff and the full diff agree on the same data;
- a read in the background → asked again when the app opens.
Field test:
1. Change one basal hour on the Veo.
2. Wait for the `S` read with an unexpected basal (or restart the app).
3. Expected: the profile read in the log, then the dialog (or the automatic switch).
4. Accept: the AAPS active profile shows the new basal, the message goes away, and no failed alarm.
5. Do it again with the app in the background.
Log markers: `onPumpProfileRead`, `asking the user`, `pump profile accepted, switched to`.
Docs: a new section in `MEDLINK_CHANGES.md`.
Decisions (user, 2026-11-14), these replace the "owner proposal" above:
- **Ask the user, do not switch by itself.** The user asked for this before (4ah "accept the pump profile",
  `MEDLINK_CHANGES.md` :741-753). But the setting `AcceptPumpProfile` is **off by default**, so today nobody
  is asked. Make asking the normal behaviour: turn the setting on by default, or drop it and always ask.
  Ask the user which, if you are not sure. "Cancel" still means "do not ask again for this same pump profile".
- **Take ALL the values** from the pump: basal, IC, ISF and targets (as `acceptPumpProfile` does today; DIA
  and insulin stay from AAPS).
Direction (user, 2026-11-14): **always pump → AAPS, never AAPS → pump.** When the pump profile is different,
ask the user if they want to update AAPS with it. Yes → update AAPS (new local profile + profile switch, as
`acceptPumpProfile` does). No → keep AAPS as it is.
- The notification `medlink_profile_differs_from_pump` must change with this. Today it ends with "MedLink cannot
  change the profile on the pump: change it on the pump too." That is the wrong direction. New text, for
  example: "The profile on the pump is different from the AAPS profile: <differences>. Update AAPS with the
  pump profile?", with the buttons **Update AAPS** (= "Use pump profile") and **Not now**.
- Today this notification is posted from `isMedLinkProfileSet` with the **requested** profile, so it shows right
  after every profile switch in AAPS (B7). Post it only after a pump profile read, comparing with the **active**
  profile.
- Note for the user (owner): after a manual switch in AAPS to a profile that is different from the pump, the
  next pump profile read will ask again. This is by design: the pump is the source of truth.

How often the pump profile is read today (owner, from the code; this explains part of the bug):
- Once at app start (`postInit`, `MedLinkMedtronicPumpPlugin.kt:1523`).
- Again only when the basal in the status `S` is not the one in the last read profile for this hour, the pump
  is Running, and the last read was more than 10 min ago (:4700-4713).
- And 4 min after the status parser sees a line it does not know in the TDD part
  (`MedLinkStatusParser.kt:425` sets `readProfile`; :1230 reads). The check `profile == null` there is always
  true: `profile` is a `private val` that is never set (:430). This is dead code or a bug; clean it up.
- **There is no regular read.** A pump change in IC, ISF, targets, or in the basal of another hour is not
  seen until the current hour's basal differs, or until the app restarts. So the user may never be asked.
- Fix, read schedule (user, 2026-11-14):
  1. at app init (as today);
  2. **when the Profiles screen (star icon, see B7) is opened**, so the user sees and is asked about the
     current pump profile;
  3. **every 6 hours** (the interval becomes a setting in T19; use a constant with 6 h now, so T19 only has to
     move it to a preference);
  4. keep the read when the basal in `S` differs (it is cheap, and it finds a changed basal quickly).
  Each read is a BLE command (E + F) that keeps the MedLink busy for a while. Never start it during a bolus
  or a TBR operation: put it after them in the queue. Do not read twice within a few minutes (for example,
  when the screen is opened again).
More tests:
- the read schedule: init, Profiles screen opened, every 6 h, basal differs; no read while a bolus or a TBR
  operation runs; no second read within a few minutes;
- a changed IC with the same basal → the user is asked at the next timed read.


## B7 — ~~Profile changed in AAPS and activated with ">" is not activated~~ — CLOSED, not a bug
**Closed (user, 2026-11-14): pressing ">" does activate the profile. Do not work on B7.**
The text below stays only because B6 and T18 point to it (the Profiles screen with the star icon, and how the
profile switch path works).
Seen: user, field, 2026-11-14: "whenever I change the profile in AAPS and click the > button, the profile isn't
activated in the app. I think this is related to enacted false."
What happens: after a profile switch (">" in the profile screen), the active profile in AAPS stays the old one.
Screen (user, 2026-11-14): the **Profiles** screen that opens with the **star icon at the bottom of the screen**
(Glass skin). Find it in the code from that icon, and write the file and line here. The ">" button there
activates the chosen profile. The same screen needs translation: see T18.
New information (user, 2026-11-14): after ">" the app shows the message
`medlink_profile_differs_from_pump` ("The basal profile on the pump is different from the AAPS profile: ...
MedLink cannot change the profile on the pump: change it on the pump too."), but **the AAPS profile is still
not changed**. What this tells us:
- The switch reaches the MedLink driver: `MedLinkMedtronicPumpPlugin.isMedLinkProfileSet` (:1811-1830) runs with
  the **requested** profile, compares it with the pump basal, and posts this message. So the message shows
  **before** the new profile is active, and also when it never becomes active.
- The message says "change it on the pump too". That makes the user think the AAPS part is done, but it is not.
  This misleads the user.
- ~~So the failure is after `isThisProfileSet` (cause 1 fits best).~~ The log below shows the EPS **was** written.
Log from the user (2026-11-14 08:58:41), after ">" on profile "std" 100 %:
```
08:58:41.753 USER ENTRY: PROFILE_SWITCH ProfileSwitchDialog [SimpleString(value=std), Percent(value=100)]
08:58:41.756 Inserted EffectiveProfileSwitch(id=12, ... originalProfileName=std, originalPercentage=100,
             originalPsId=12, basalBlocks=[0.75 x2h, 0.8, 0.75, 1.0 x2h, 1.1 x2h, 1.3, 1.35, 1.3, 1.2, 1.1 x2h,
             1.2 x3h, 0.9, 0.95 x5h, 1.3], isf=[45 x8h, 35 x2h, 40 x2h, 35 x3h, 40 x9h], ic=[12 x17h, 10 x7h],
             target 90/90 mg/dL)
```
**What this proves:** the switch **is** applied in AAPS. The EPS is written 3 ms after the profile switch. So
cause 1 below (`runBlocking` / timeout) and causes 2–4 did **not** happen in this case. Look somewhere else:
- **A. The edits were not in the profile that was switched to.** The user "changes the profile" and presses ">".
  If the editor's changes are not saved to the profile store before the switch, the EPS gets the **old** values.
  Compare the EPS values above with what the user typed. Check whether ">" saves first, or asks to save.
- **B. The UI does not show it.** The name / % on the dashboard (Glass, circle-top), the Profiles screen and the
  graph basal may not refresh after the EPS. Check what the user looks at to say "not activated".
- **C. Something replaces it later.** Look in the log after 08:58:41 for another `PROFILE_SWITCH` /
  `Inserted EffectiveProfileSwitch` / a profile switch from `Sources.Medtronic` (B6 accept), or a running PS
  with a duration that ends.
- **D. The message.** The message "different from the pump" (see above) makes the user think the switch failed,
  although it worked.
Ask the user which of these they see. The causes 1–5 below stay as checks for other cases (for example a
profile switch while the MedLink is busy).
Extra requirements (owner):
- The "different from the pump" message may only show **after** the new profile is really active (EPS
  written). Compare the pump with the **active** profile, not with the requested one: move the compare out of
  `isThisProfileSet`, to after the EPS is written, or to the profile-changed event.
- If the switch fails: show a clear failure message ("Profile X was not activated: <reason>") and no
  "change it on the pump" message.
- After a good switch with a pump that is different: the message says that AAPS now uses profile X. It also has
  the B6 button "Use pump profile", so the user can choose the pump profile instead.
- Log one line for each step: requested → command run → result → EPS written (or why not).
What the owner found in the code (not proven; the developer must check it with the log):
- The path is: profile switch (PS) saved → `CommandQueueImplementation` `onProfileChanged` (:150-190) →
  `setProfile` (:597) → for MedLink, `MedLinkCommandSetProfile`
  (`implementation/.../queue/commands/MedLinkCommandSetProfile.kt`, 4v) → it answers
  `success(true).enacted(false)` → `postProfileWriteResult` (:217) returns true on `success`, so the
  EffectiveProfileSwitch (EPS) is saved. **By this reading, `enacted=false` alone should NOT block the
  profile.** So check these causes, in this order:
  1. **`runBlocking` inside the suspend `execute()`** of `MedLinkCommandSetProfile` (two calls). On the queue's
     coroutine this can block or deadlock. Then `setProfile` never completes, `withTimeoutOrNull(PROFILE_SET_TIMEOUT_MS)`
     gives `null`, `postProfileWriteResult(null)` → FAILED_UPDATE_PROFILE, and **no EPS**. Look for
     "setProfile timed out" in the log. Fix: call the suspend functions directly (no `runBlocking`).
  2. Is the callback result set to the command's return value for this command? `deferred.complete(result)`:
     if `result` stays at its default (`success=false`), no EPS is saved.
  3. `setProfile` early returns: `isRunning(BASAL_PROFILE)` → success / not enacted, but is a new EPS made? And
     `isThisProfileSet(profile) && EPS active` → "Correct profile already set": MedLink `isThisProfileSet`
     is always true (4v), so this depends only on `requestedProfile.isEqual(runningProfile)`. Check that
     percentage / timeshift / IC / ISF changes are not seen as "equal".
  4. The `onProfileChanged` skip "active EPS already represents PS id" (:154).
  5. The UI: does the ">" button really create a PS (`createProfileSwitch` returns true)? Or does it wait for
     `enacted` to show success?
- 4v and the field test in `MEDLINK_CHANGES.md` (:489-506) say the switch worked then ("profile accepted in
  AAPS only"). Look for what changed since then: the AAPS 4.0 merge (`91e61dfdaf`) moved the queue to suspend
  functions. That is a likely regression point.
What should happen: a profile switch in AAPS becomes active at once (new EPS). The pump is not written (agreed
rule, the Veo cannot be written). The "pump profile is different" message (B6 / 4v) may then show, as
expected.
Safety: P1. The loop and the TBR micro-bolus amounts use the active AAPS profile. If the switch is silently not
applied, the user thinks a profile is active that is not. Show a clear failure message if it fails. Never fail
silently.
Where: `MedLinkCommandSetProfile.kt`; `CommandQueueImplementation.kt` :150-230, :597-625, :813;
`MedLinkMedtronicPumpPlugin.isMedLinkProfileSet` :1811; the profile screen with the ">" button.
Tests needed (first):
- `MedLinkCommandSetProfile.execute()` returns `success=true` and does not block (run it in `runTest` with a
  single-thread dispatcher);
- `onProfileChanged` with MedLink active → an EPS is saved, also with `enacted=false`;
- a timeout → a failure message and no EPS;
- the same profile again → no new EPS and no error;
- the "different from the pump" message is posted only after the EPS is written, and it compares the active
  profile; a failed switch → the failure message, and no "change it on the pump" message.
Field test:
1. In AAPS, switch to another profile (or 90 %) with ">".
2. The Overview shows the new profile name or % within a few seconds.
3. The log has "MedLink: basal profile not written to the pump, accepted in AAPS only", and no
   "setProfile timed out".
4. Restart the app: the profile is still active.
Related: B6. Its "use pump profile" also calls `createProfileSwitch`, so it needs this path to work. **Fix B7
first.** Docs: a section in `MEDLINK_CHANGES.md`.

## B8 — After Stop during a bolus, the bolus dialog stays open and blocks the screen
Priority: P1 (the command queue may be blocked for up to 10 min: no TBR, no loop action, no new bolus)
Seen: user, field, 2026-11-14: "when a bolus is being delivered and I press Stop, the bolus dialog keeps showing
and it blocks the screen." (The stop itself works: field tested in 4ai / `MEDLINK_CHANGES.md` :677.)
What the owner found in the code (not proven; check with the log):
- `MedLinkCommandBolus.execute()` (`implementation/.../queue/commands/MedLinkCommandBolus.kt`) waits with
  `MedLinkCallbackBridge.awaitResult` (**10 min** timeout, `DEFAULT_TIMEOUT_MS`) for the driver's answer. The
  dialog closes **only** after that (`completeAndAutoClear` / `clear`), or in `cancel()`.
- The driver answers through `bleBolusCommand.onQueueResult` (`MedLinkMedtronicPumpPlugin.kt:4050-4062`).
  `stopBolusDelivering()` (:2026-2065) sends a USER STOP and expects "the tracker reads M and records the
  partial amount". **Check that this path really calls `onQueueResult`.** Possible gaps:
  - The USER STOP drops the bolus executor from the queue (T0: the user stop goes first and drops the boluses
    not sent). `onDropped` is only for `QUEUED` requests, so a written bolus may never get a result.
  - The tracker gets `PARTIAL` but no `Record` / `onQueueResult` comes for it. Or it waits for more `M` reads
    that are never sent, because the pump is now suspended.
  - The low-battery branch (:2034-2042) shows an alarm and returns. The bolus goes on, and the dialog
    shows Stop as pressed with no feedback.
- `bolusProgressData.stopPressed()` is set by the dialog. Nothing in the MedLink path reads `isStopPressed` or
  updates the dialog text ("stopping...").
- The dialog is modal. While it waits, the user cannot use the app ("blocks the screen").
What should happen:
- After Stop: the dialog says "Stopping…", then "Stopped: X.XX U of Y.YY U delivered" (format-string resources),
  and closes by itself in a few seconds. The partial amount is recorded (it is today). The queue goes on.
- There is always an end within a short time:
  - The stop path must end the bolus command with a result, `success=true` with the delivered amount
    (`PARTIAL`; T7 rules: never more than asked).
  - If `M` can't be read, end with "unknown, check the pump" and a clear alarm, not 10 minutes of waiting.
- If the stop is refused (low battery): the dialog says so and lets the user close it. The bolus goes on, and
  the dialog shows the progress again.
- The user can always close (hide) the dialog. Hiding does not cancel anything.
Where: `MedLinkMedtronicPumpPlugin.kt` :2026-2065, :4030-4090; `MedLinkCommandBolus.kt`; `MedLinkCallbackBridge.kt`;
`MedLinkBolusTracker` (stop / partial / dropped paths); `MedLinkBLE` user stop (queue drop of executors);
`BolusProgressData` (`stopPressed`, `clear`).
Tests needed (first, DEV.md):
- tracker: a written bolus, then a USER STOP, then `M` with a partial amount → `Record(partial)` and a result
  for the queue, exactly once;
- the same with `M` failing → a final state with an alarm, and the result sent;
- the bolus executor dropped by the user stop → the result is still sent;
- `MedLinkCommandBolus` with a result after the stop → the dialog is cleared and the queue goes on;
- the recorded amount is never more than the requested amount.
Field test:
1. Give a manual bolus of 2 U.
2. Press Stop at about 0.5 U.
3. Expected: the dialog shows "Stopping…", then the partial amount, and it closes within about 30 s.
   Treatments show about 0.5 U. The app can be used at once. The pump runs again (or stays suspended if the TBR
   wants it).
Log: `bolus stop: bolus DELIVERING, stopping the pump`, `BOLUS[id] state ... -> PARTIAL`,
`NORMAL bolus 2.0U result success=true delivered=0.5`, and **no** "MedLink did not answer the bolus".
Docs: a section in `MEDLINK_CHANGES.md`.

## B9 — No "profile updated" message after a profile switch with MedLink
Priority: P3
Seen: user, 2026-11-14: "Although the profile is updated, the 'profile has been updated' notification or message
is missing."
What happens: the profile switch works (EPS written, see the B7 log), but the user gets no confirmation.
Cause (owner, from the code): `CommandQueueImplementation.postProfileWriteResult` (:217-226) posts
`NotificationId.PROFILE_SET_OK` only when `result.enacted && !silent`. `MedLinkCommandSetProfile.execute()`
always answers `success(true).enacted(false)` (the pump is not written). So the message never shows. This is
the "enacted false" the user saw.
What should happen: after a profile switch that becomes active with MedLink, show a short message with the
profile name and %, for example "Profile std 100 % is active in AAPS". Use the same notification id and time
(`PROFILE_SET_OK`, 60 min) so it replaces / is replaced like the upstream one.
- **Do not just answer `enacted(true)`.** That would show the upstream text "Basal profile in pump updated",
  which is false for MedLink (the pump is not written; agreed rule). It may also change other logic that reads
  `enacted` (SMS, Nightscout, profile-changed events). Use a MedLink text: a new string resource with
  placeholders, translated to pt-BR.
- The same message after "Update AAPS" from B6.
- Not for silent / automatic switches (`silent`, for example a Scene reverting its own profile switch). Same
  rule as upstream.
- Keep the MedLink change small and marked (`CommandQueueImplementation` is upstream code; list it in the merge
  notes).
Where: `implementation/.../queue/CommandQueueImplementation.kt` :150-226; `MedLinkCommandSetProfile.kt`; strings in
`core/ui` or `pump/medtronic`.
Tests needed: a MedLink switch that is not silent → the message with the name and %; silent → no message; another
pump → the upstream message is unchanged.
Field test: switch the profile with ">" → the message "Profile <name> <%> is active in AAPS" shows. Accept the pump
profile (B6) → the same message with the new name.
Related: B6, B7 (closed).
