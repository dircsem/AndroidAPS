Status: OPEN

# Development tasks

## T14 — Check the warning colors of every dashboard status box
Priority: P2
Seen: user, 2026-11-14, together with B5 ("check the other buttons").
What to do: for each skin, check every status box against the AAPS status lights. Write the result in a
table (box x skin -> OK / problem). Fix the problems found, or write each one as a new `B` item for the
owner.
- Skins: circle-top / new skin (`DashboardCircleTopCompose.kt`, `PumpCompactBadgeRow` `:601`), Glass
  (`StatusAgoraCard.kt`, mapping in `GlassOverviewComposeEmbedded.kt:485-505`), and the XML hybrid view
  (`CircleTopDashboardView.kt`, `component_circle_top_status_hybrid.xml`) if it is still used.
- Boxes: reservoir, pump battery (B5), site / cannula, sensor, MedLink battery, "Pump unreachable", and the
  TBR / basal pills if they have a color.
- For each box, check that:
  1. The text and the color come from the same value. On circle-top the text is from `OverviewViewModel`
     and the color from `StatusViewModel`.
  2. The right threshold keys are used (age: `Overview*ageWarning/Critical`; level: `OverviewResWarning/
     Critical`, `OverviewBattWarning/Critical`, `OverviewSbatWarning/Critical`).
  3. The color changes when the value changes (refresh), not only after a restart.
  4. Both light and dark themes show a clear yellow and red.
- Already seen, check these first:
  - Glass reservoir is colored by the **insulin age** (`insulinAgeStatus`, `GlassOverviewComposeEmbedded.kt:492`),
    not by the reservoir level. Circle-top uses the level (`DashboardCircleTopCompose.kt:616`). The two skins
    disagree; ask the owner which one is right (maybe the worse of the two, like the battery).
  - Glass pump battery: age only (see B5).
  - Circle-top site: age only. That is fine, because the cannula has no level threshold.
  - The sensor badge on circle-top: find where it gets its color.
Tests needed: one test per box for the color decision (normal / warning / critical / no data). Reuse one
plain function for all the skins, so they cannot drift apart again.
Field test: on each skin, change the thresholds in the preferences so each box crosses warning and then
critical. Take a screenshot of each state and send it to the user.
Related: B5.

## T15 — Glass skin: clear titles for all pills (pt-BR, en, ru, pl)
Priority: P3
Seen: user, 2026-11-14. "I want Portuguese texts today, you can also improve English, Russian and Polish.
The best description with about 15 characters. Revise all buttons there."
What to do: give every pill on the Glass skin (`StatusAgoraCard.kt`) a clear title of **15 characters or
less** in `values` (en), `values-pt-rBR`, `values-ru-rRU` and `values-pl-rPL`. Today they are cryptic
("P bat", "M bat", "L bolus", "Bas 30m"), and ru/pl have none (they show English).
- Pills that use their own Glass strings (change in place):
  `dashboard_glass_pump_battery_short` (:171), `dashboard_glass_medlink_label` (:190),
  `dashboard_glass_tbr_short` (:208), `dashboard_compact_label_delivered` (:216, placeholder `%1$d` = minutes),
  `dashboard_glass_loop_label` (:325), `dashboard_glass_activity_label` (:378),
  `dashboard_glass_target_label` (:394), `dashboard_glass_last_bolus_short` (:404),
  `dashboard_glass_tools_label` (:413).
  - `dashboard_compact_label_delivered` is also used outside Glass. Check its other uses (grep) before you
    change it; if the new text does not fit there, make a Glass-only string.
- Pills that take their title from shared AAPS strings: `insulinLabel` (:137), `cannulaLabel` (:154), and
  `sensorLabel` (:305). These come from `StatusViewModel` (`insulin_label`, `sensor_label`, ... in `core/ui`).
  **Do not change the shared strings**; other screens use them. Add new Glass strings
  (`dashboard_glass_*_label`) and use them in `GlassOverviewComposeEmbedded.kt:491-505`. The Glass pills
  show an **age** as the value, so the title should say it.
- Final texts (approved by the user, 2026-11-14; at most 15 chars each):

  | pill | en | pt-BR | ru | pl |
  |---|---|---|---|---|
  | reservoir / insulin age | Insulin age | Idade insulina | Срок инсулина | Wiek insuliny |
  | cannula age | Cannula age | Idade cânula | Срок канюли | Wiek kaniuli |
  | pump battery | Pump battery | Bateria bomba | Батарея помпы | Bateria pompy |
  | MedLink battery | MedLink battery | Bateria ML | Батарея MedLink | Bateria MedLink |
  | temp basal | Temp basal | Basal temp. | Врем. базал | Baza tymcz. |
  | basal in last N min | Basal %1$d min | Basal %1$d min | Базал %1$d мин | Baza %1$d min |
  | sensor age | Sensor age | Idade sensor | Срок сенсора | Wiek sensora |
  | loop | Loop | Loop | Цикл | Pętla |
  | activity | Activity | Atividade | Активность | Aktywność |
  | target | Target | Alvo | Цель | Cel |
  | last bolus | Last bolus | Último bolus | Посл. болюс | Ostatni bolus |
  | tools | Tools | Ferramentas | Инструменты | Narzędzia |

  ru and pl must use the words the existing AAPS ru/pl translations use (check `core/ui` `values-ru-rRU` /
  `values-pl-rPL`, for example the word for "loop" and "temp basal"). If AAPS uses a different word than the
  table, tell the user before you change the approved text.
- `comment=` notes in `values/strings.xml`: change "keep it very short" to "pill title, at most 15
  characters". Explain the placeholder in `dashboard_compact_label_delivered`.
- Strings rule exception: the user asked for these pt-BR, ru and pl texts, so edit those `values-xx` files
  directly. These are fork strings (`dashboard_glass_*`) and not part of the AAPS Crowdin project. If one
  of them is in Crowdin, tell the user, because Crowdin would overwrite it.
- Fit: the pills are `100.dp` wide (`StatusAgoraCard.kt:175`, `:194`, and others). 15 characters may not fit.
  Check each language with the largest system font. If a title is cut, ask the user before you change the
  width, the font or the text. Do not use hardcoded sizes; use theme values (CLAUDE.md).
Tests needed: none for strings. Build `:plugins:main` once to check resources and placeholders.
Field test: open the Glass skin with the phone in Portuguese (and in English if possible). Check every pill:
the full title is visible, it is not cut, and it reads well.

## T16 — Glass skin: "Now" and "Next" MedLink commands as plain text, not boxes
Priority: P3
Seen: user, 2026-11-14 ("now and next command at this skin can be just a text instead of a box").
What happens: at the bottom of the Glass card, MedLink pumps show two full-width boxes
(`BottomMetricPill`): "Now" = the MedLink command running now (`state.medLinkCommandNow`), "Next" = the next
pump operation (`state.medLinkCommandNext`). Code: `StatusAgoraCard.kt:467-482` (MedLink only, shown when
`medLinkDeliveredPercent != null`; keep that marker comment for upstream merges).
What should happen: two lines of plain text with no box, background or border, for example:
  `Now: Bolus 0.3 U`
  `Next: Reactivate at 14:32`
- Use a format-string resource for each line, for example `dashboard_glass_command_now_line` = `%1$s: %2$s`
  or "Now: %1$s". Do NOT join title + ": " + value in code (CLAUDE.md). Add `comment=` with the placeholders
  and an example. Add pt-BR ("Agora: %1$s", "Próx.: %1$s"), ru and pl. Same rule as T15.
- Style: theme typography and colors (for example `MaterialTheme.typography.bodySmall`,
  `onSurfaceVariant`), and the same text color rules for light and dark as the other Glass texts. No
  hardcoded sizes or colors. Keep the existing spacing values or use theme spacing.
- One line each, `maxLines = 1` with an ellipsis, so a long command does not push the card down.
- Unknown value (null or blank): **hide that line** (user, 2026-11-14). When both are unknown, show nothing and
  no spacer, so the card gets shorter.
- Remove the code and strings that are no longer used (`dashboard_glass_command_now/next` if replaced).
- If T15 is done first, these two lines need no 15-character limit (they are not pills).
Tests needed: none (UI only). Build `:plugins:main`.
Field test: Glass skin with the MedLink pump, while a TBR plan is running. The two lines show as text, the
values update when the command changes, and they are readable in light and dark theme.
Related: T15 (same skin, same strings files; do them together).

## T17 — Glass skin: dynamic pill layout from the user's selection, using the least height
Priority: P3
Seen: user, 2026-11-14: "organize it according to the ones selected, each user may have a different
selection. Today they are fixed at the columns, but this should be dynamic." Also: "top down, to occupy the
lowest vertical space".
What happens today (`StatusAgoraCard.kt`):
- Each pill is written by hand in a fixed column:
  - left (:130-224): reservoir, cannula, battery, MedLink battery, TBR, basal delivered;
  - glucose ring in the center (`TIR_RING_SIZE`, :226);
  - right (:298-428): sensor, loop, activity, target / last bolus, tools.
- The bottom row (:441) shows the `BOTTOM_ROW` pills of the catalog, minus `SIDE_COLUMN_PILLS`.
- When the user turns pills off in the personalization (`GlassPersonalization.kt`,
  `StringNonKey.GlassSelectedPills`), the place stays fixed: one column can be empty while the other is
  long, and the card stays tall. The catalog KDoc (:25-28) even says the top grid order is fixed in code.
What should happen:
- The layout is computed from the pills that are **really shown**: the user's selection, plus the pills
  that exist only in some cases (MedLink battery, basal delivered, TBR when active, tools).
- Fill **top-down**: put pills into the slots beside the ring (left column, then right column, from the top),
  then into rows below the ring. Choose the split with the **smallest total height**:
  - Both side columns hold at most as many pills as fit beside the ring.
  - The two columns differ by at most one pill.
  - The rest goes into full bottom rows. A row is filled before a new one starts.
- Order = a fixed priority list in code (pump group first, then CGM / loop, then the others), so the
  user always finds a pill near the same place. If the user wants to choose the order, that is a
  separate task; ask the owner.
- No holes, no empty columns, no spacer for a missing pill.
- The T16 Now/Next lines stay last and take no space when hidden.
- Keep each pill's tap / long-press action, its color (B5 / T14) and its title (T15).
- Design:
  - Make one pill descriptor: id, title, value, color, icon, onClick.
  - Write a plain function `layoutGlassPills(shown, slotsBesideRing, pillsPerRow)` that returns
    left / right / rows.
  - The Composable only draws that result.
  - This removes the hand-written column code and makes the rule testable.
  - Update the catalog KDoc. Merge `TOP_GRID` / `BOTTOM_ROW`: placement no longer depends on it. Keep the
    saved selection format (`GlassSelectedPills`) so users keep their choice.
- Use theme spacing and sizes, not new hardcoded dp (CLAUDE.md). The repeated `100.dp` / `8.dp` should
  move to the theme while you are there. Discuss it first if no theme value fits.
**Before coding:** show the user an ASCII sketch for 3 selections: everything on with MedLink; the default
selection without MedLink; and only 3 pills. Wait for an OK.
Tests needed: unit tests for `layoutGlassPills`:
- 0, 1, odd and even counts;
- more pills than the slots beside the ring;
- the MedLink-only pills present and absent;
- the order stays stable when one pill is removed;
- no empty slot comes before a filled one.
Build `:plugins:main`.
Field test: Glass skin on the phone. Turn pills on and off in the personalization: the card re-arranges at
once, has no holes, is never taller than before, and every tap still opens the right screen. Take screenshots
before and after with the same selection, and give the height saved.
Related: T15, T16 (same file; do T15 → T16 → T17 in one go so the titles have their final size first).

## T18 — Translate the Profiles screen (star icon at the bottom)
Priority: P3
Seen: user, 2026-11-14: the Profiles screen, opened by the star icon at the bottom of the screen (Glass skin),
"needs translation".
What to do:
- Find the screen. It is the same one as in B7.
- List every text on it that shows in English with the phone in Portuguese.
- For each text:
  - Fork / AIMI string with no pt-BR: add pt-BR, and also ru and pl (same rule as T15: the user asked for these
    languages, and edit the `values-xx` files directly).
  - Hardcoded text in code: move it to a string resource first (CLAUDE.md: no hardcoded UI text, no joined
    strings).
  - Upstream AAPS string (in Crowdin): do not edit `values-xx`; tell the user, because it comes from Crowdin.
- Use the words that AAPS already uses in pt-BR / ru / pl (profile = "Perfil", and so on).
Tests needed: none. Build `:plugins:main` (or the module of the screen).
Field test: phone in Portuguese → open the star icon → every text on the screen is in Portuguese.
Related: B7 (same screen; do T18 after B7 or together).

## T19 — Setting: interval of the MedLink pump profile read
Priority: P3
Seen: user, 2026-11-14 ("every 6 hours, you can add a task to make this configurable").
What to do: B6 reads the pump profile every 6 h, with a constant. Make the interval a MedLink preference:
- a new `IntKey` in the MedLink keys (next to `MedLinkMedtronicBooleanPreferenceKey.AcceptPumpProfile`), in hours;
  default 6, min 1, max 24; and 0 = "no timed read" (only at init, on the Profiles screen, and when the basal differs);
- shown in the MedLink preferences next to "Accept profile changes from the pump", with a title and a summary in
  simple English (+ pt-BR, as for T15). The summary says that each read keeps the MedLink busy for a while;
- the timer uses the new value at once after a change (no app restart).
Tests needed: the timer with 0, 1 and 6 h, and a change of the value while running.
Field test: set 1 h, and check the log: one profile read about every hour, never during a bolus.
Related: B6 (do B6 first).

## T20 — Alarm with sound and flashing light after 4 failed pump connections
Priority: P1 (insulin may stop with no alarm)
Seen: user, 2026-11-14: "If the pump can't be connected for 4 'ok conn' commands, the user should receive an
alert with sound (configurable) and a flashing light."
What happens today (owner, from the code):
- `BleConnectCommand.kt:20-24`: an answer "pump no response" to the connect command calls
  `MedLinkBLE.pumpConnectionError()` (:2087). This counts `noResponse`. At **more than 2** it only sends the
  broadcast `MedLinkConnectionError`. There is no alarm for the user. `clearNoResponse()` resets the count on "ready".
- `MedLinkMedtronicPumpPlugin.isPumpNotReachable` (:2069) **always returns false**, so every "pump unreachable"
  branch in the plugin (:1089, :1469, :1627, :2217, `setNewBasalProfile`) is dead code. Fix it or remove it,
  and use the new counter instead.
- AAPS has its own time-based alarm "Pump unreachable" (`BooleanKey.AlertPumpUnreachable`,
  `IntKey.AlertsPumpUnreachableThreshold`, in minutes). The Glass / circle-top red box uses the same rule.
  Keep it. The new alarm is MedLink only and based on the count, so it comes sooner.
What should happen:
- Count failed pump connections. A failure is: "pump no response" after OK+CONN, or no answer to OK+CONN
  in time. Any good pump answer resets the count.
- At **4** in a row (a MedLink setting, default 4): raise an alarm that the user notices:
  - **sound**: a MedLink setting to turn it on or off and to pick the sound (reuse the AAPS alarm sound
    choice if there is one). Default on;
  - **flashing light = the phone's camera flashlight** (user, 2026-11-14). Blink it (for example 0.5 s on /
    0.5 s off) with `CameraManager.setTorchMode` on the back camera that has a flash. Rules:
    - stop it on OK, on Silence, when the connection comes back, and when the app is closed. **Never leave the
      torch on.** Turn it off in a `finally` block and in `onDestroy` of the alarm service.
    - The phone has no flash, or the camera is in use (`CameraAccessException` / torch callback "unavailable"):
      skip the light, log it, and still give the sound and the notification.
    - Check that the alarm runs in the background, with the screen off (foreground service or the AAPS alarm
      service). Check whether a permission is needed on our minimum SDK; add none without asking.
    - A MedLink setting turns the flashlight on or off (default on);
  - an Android notification with a high priority that can be seen on the lock screen.
  - User controls (user, 2026-11-14):
    - a MedLink setting turns this alarm on or off;
    - **OK** stops the alarm for this outage;
    - **Silence 15 min** stops the sound and light for 15 min; if the pump is still not reached then, the
      alarm comes back.
    - If the connection comes back, the alarm stops by itself.
- When the connection comes back: stop the alarm, and show "Pump connection restored" once.
- Never alarm twice for the same outage. Do not alarm while the user has disconnected the pump on purpose
  (AAPS "disconnect pump" / running mode).
Where: `BleConnectCommand.kt`, `MedLinkBLE.kt` :405, :2083-2092; `MedLinkMedtronicPumpPlugin.kt` :2069; MedLink
preference keys (`MedLinkMedtronicIntPreferenceKey`, the Boolean keys).
Tests needed (first): a rule class (for example `MedLinkConnectionAlarmRule`): 3 failures → no alarm; 4 → alarm
once; a success in between → reset; the connection back → alarm and torch off; the pump disconnected on purpose → no alarm;
the torch not available → sound and notification still given;
the setting off → no alarm; OK → no new alarm in the same outage; silence → the alarm again after 15 min if
still failing, and not if the connection came back.
Field test: take the phone with MedLink away from the pump (or remove the pump battery). After 4 connection
tries: sound, light and notification. Bring it back: the alarm stops and the "restored" message shows. In the
log: the count of each failure and the alarm raised / cleared.
Docs: a section in `MEDLINK_CHANGES.md`.
Related: T21.

## T21 — Zero temp basal in AAPS when the pump battery is old (red) and the pump can't be reached
Priority: P1 (it can cause over-delivery if done wrong; follow the decisions below exactly)
Seen: user, 2026-11-14: "If the battery is set to age, the age is red and the pump can't be contacted (ok conn
fails several times), a temp basal of 0 should be added until the connection is back and the status can be
checked."
Idea: an old battery plus no answer probably means **the pump battery is dead and the pump delivers nothing**.
AAPS still counts the scheduled basal as delivered, so its IOB is too high and the loop doses too little.
A 0 % temp basal in AAPS makes the IOB match "no insulin".
Owner concerns (must be solved in the design):
1. **This TBR is only a record in AAPS.** The pump cannot be reached, so nothing can be sent to it. If the pump
   is in fact running (only out of range, or the MedLink is dead), the pump keeps giving the scheduled basal
   while AAPS counts 0. Then the IOB is too **low**, and when the connection comes back the loop gives
   **too much insulin**. That breaks the main rule (DEV.md).
2. Decision (user, 2026-11-14): **a dead battery leaves no "battery change" event on the pump**, so the pump
   history cannot tell if it stopped. So **when the pump connects again, ask the user**: "Keep the 0 % basal
   stored from <start> to <end>?"
   - **Yes** → keep the record. It ends at the reconnect time.
   - **No** → **remove the record from the database** (invalidate it with `pumpSync` / the persistence layer, the
     same way as other removed treatments), so the IOB counts the scheduled basal again.
   - To help the user, the dialog shows the facts: the outage start and end, the reservoir before and now (from
     `S`), and the scheduled basal of that time. If the reservoir went down by about that basal, the pump was
     probably running. Write it in one simple sentence; the user decides.
   - The question must not get lost (see B6 point 3). It stays as a notification with the two buttons, and it
     comes back when the app opens, until the user answers.
   - **While the question is not answered (user, 2026-11-14): no SMB and no TBR above 100 %** (no TBR micro
     bolus). TBR at or below 100 % and suspends are allowed. This is because with "No" the IOB would be too
     low. Do it as a MedLink constraint (like the other MedLink limits in the loop), with a clear reason in the
     loop status: "waiting for your answer about the 0 % basal". It ends at once after the answer.
3. While the 0 % is active and the pump is not reached, no automatic bolus may be planned (SMB, TBR micro
   bolus). This already holds when the pump cannot be reached. Check that nothing is queued and then sent late
   on reconnect (the 5 min lifetime rule of the bolus queue).
Conditions: the MedLink "battery info" = age, and the battery age status is CRITICAL (`OverviewBageCritical`),
and the T20 failure count is reached (the same counter and setting, default 4; the user did not ask for a
separate number).
What should happen after the answers: the TBR 0 % is written with `pumpSync` like the other MedLink TBRs, with a
clear note ("pump not reached, battery old"). It starts at the time of the first failed connection of the
outage, and it ends when the pump answers again. Then the user is asked (point 2). The user sees it in the
treatments and in the dashboard.
Tests needed (first): the start condition (age not red → no TBR; level mode → no TBR; fewer than N failures → no
TBR); reconnect → the question is asked once; "Yes" → the record is kept and ends at the reconnect; "No" → the
record is removed and the IOB counts the scheduled basal; no answer → the question comes back, and the loop limit
holds (no SMB, no TBR > 100 %; TBR <= 100 % allowed); the limit ends after the answer; no bolus is sent late after
reconnect.
Field test: (with care, at a safe glucose, the user present) set the battery age critical threshold low so the
age is red, then remove the pump battery. After N failures: the TBR 0 % shows in AAPS. Put the battery back:
the question comes; answer "Yes": the record stays and ends at the reconnect. Do it again out of range, with the
battery in, and answer "No": the record is gone from the treatments and the IOB goes up again.
Related: T20 (the same failure counter), B5 (the battery colors / age status).
