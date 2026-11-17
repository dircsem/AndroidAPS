Status: STARTED (2026-11-14, developer)

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

### Developer notes (2026-11-14) — PART done (audit from the code); one question for the owner
Audit (code only; thresholds and light / dark not checked on a phone):

| box | circle-top (Compose) | Glass | XML hybrid view |
|---|---|---|---|
| reservoir | level status (`insulinStatus.levelStatus`) — OK | **insulin AGE status** (`insulinAgeStatus`) — differs | text only, no color |
| pump battery | worse of age / level — OK (B5) | was age only → **fixed in B5** (worse of the two, level shown) | text only, no color |
| site / cannula | age status — OK | age status — OK | text only, no color |
| sensor | badge, **no color** (text only) | age status — OK | — |
| MedLink battery | level status — OK | level status — OK | — |
| Pump unreachable | always red (CRITICAL) — OK | — | — |
| TBR / basal pills | no warning color (not a status box) | no warning color | warning icon tint only |

- Text and color come from the same `StatusUiState` item now on Glass; on circle-top the **text** comes from
  `OverviewViewModel` and the **color** from `StatusViewModel` (not changed; both read the same pump values, but a
  refresh can be a few seconds apart).
- Shared function `worseStatusLevel` / `levelOrAgeBox` (B5) is now used by both skins for the battery.
- **Answer (user, 2026-11-14): on Glass use the worse of the two.** Done: Glass `insulinStatus` = worse of the insulin
  age status and the reservoir level status (`OverviewResWarning/Critical`). The value stays the insulin age (title
  "Insulin age"). Before, a low reservoir never colored the Glass pill.
- User field report: on Glass only the MedLink battery pill colors. Explained by the code: the MedLink battery pill
  uses a **level**; the pump battery (before B5) and the reservoir (before this fix) used only the **age**, and cannula /
  sensor use only the age. An age pill stays in the normal text color until the age reaches the warning hours, and is
  gray when there is no change event (for example no `CANNULA_CHANGE` / `SENSOR_CHANGE` in the treatments). Field test:
  lower `OverviewCageWarning` / `OverviewSageWarning` below the current age and check the pill turns yellow.
- Not done: the sensor badge on circle-top has no color; the XML hybrid view has no colors at all. Should they get
  colors (new items), or is text enough there?
- **User (2026-11-16): colors, the same standard as the battery and the cannula age.** Done:
  - circle-top sensor badge (`HeroCgmCompactBadge`): background warning / critical like the pump badges (shared
    `statusBadgeBackground`), level = `worseStatusLevel(sensor age status, sensor battery level status)` — the same rule
    as the pump battery (`OverviewSage*`, `OverviewSbat*`). The smoothing quality border stays.
  - Glass sensor pill: the same worse-of-two rule (before: age only).
  - XML hybrid view (`CircleTopDashboardView` in `DashboardFragment`): **not changed** — `DashboardFragment` is only
    registered in Dagger and never opened (no code shows it). Coloring it would need the status lights view model
    in an unused screen. Tell me if you still see it somewhere.
  - Tests: `StatusBoxLevelsTest` + 3 sensor cases (11/11). Build `:plugins:main` OK.
  - Field test: lower `OverviewSageWarning` below the sensor age: the circle-top sensor badge and the Glass sensor pill
    turn yellow; with a CGM that gives a sensor battery, a low battery also colors them.

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

### Developer notes (2026-11-14) — done, needs field test; 3 word questions
- en titles changed in `plugins/main/src/main/res/values/strings.xml` (comment now "Glass pill title, at most 15
  characters"). New Glass-only strings: `dashboard_glass_insulin_age_label`, `..._cannula_age_label`,
  `..._sensor_age_label`, `dashboard_glass_delivered_label` ("Basal %1$d min"). `dashboard_compact_label_delivered`
  stays as it was: circle-top and the XML view still use it ("Bas 30m").
- The Glass card now uses these strings directly. Removed the state fields `insulinLabel`, `cannulaLabel`,
  `sensorLabel`, `batteryLabel` and their hardcoded English fallbacks ("Insulin", ...). The shared AAPS strings are
  not changed.
- **Crowdin:** `plugins/main/.../values-xx/strings.xml` is a Crowdin file (`crowdin.yml:30`); a download would remove
  fork strings. So the pt-BR / ru / pl Glass texts are in a new file `values-xx/strings_glass.xml` (not in Crowdin).
  I moved the 10 existing pt-BR fork lines (`dashboard_glass_*`, `dashboard_compact_label_delivered`) there too.
- All texts are the approved table, each 15 characters or less (checked by the script).
- AAPS words (user answer 6, 2026-11-14: switch all): ru cannula "Срок катетера", ru activity "Нагрузка", pl
  activity "Ćwiczenia".
- Not checked: the fit in the 100.dp pills with the largest system font (needs the phone).
- **User field test (2026-11-14): "Bateria bomba" and "Último bolus" (12 chars) are too long → limit 11 characters**
  (the user said 12, but a 12-char title is still cut). All Glass pill titles in en / pt-BR / ru / pl checked by a
  script (placeholder filled with 30): pt "Bat. bomba", "Últ. bolus", "Idade ins.", "Idade cân.", "Idade sens.",
  "Bas. 30 min"; en "Pump batt.", "ML battery", "Bas. 30 min"; ru "Бат. помпы", "Батарея ML", "Баз. 30 мин",
  "Срок инсул.", "Срок катет.", "Срок сенс."; pl "Bat. pompy", "Bateria ML", "Wiek insul.", "Wiek kan.",
  "Wiek sens.", "Ost. bolus". `comment=` now says "at most 11 characters". `:plugins:main` compiles.
- Build: `:plugins:main` compiles (resources merged, no duplicates).

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

### Developer notes (2026-11-14) — done, needs field test
- `StatusAgoraCard`: the two `BottomMetricPill` boxes are now two `Text` lines (`bodySmall`, the card's `neutralColor`
  light / dark rule, `maxLines = 1` with an ellipsis). Spacing from the theme (`AapsSpacing.medium` / `extraSmall`).
- A null or blank value hides its line; both unknown → nothing and no spacer.
- Strings: `dashboard_glass_command_now_line` = "Now: %1$s", `dashboard_glass_command_next_line` = "Next: %1$s" (with
  `comment=`); pt-BR "Agora: %1$s" / "Próx.: %1$s", ru "Сейчас: %1$s" / "Далее: %1$s", pl "Teraz: %1$s" / "Dalej: %1$s"
  (in `strings_glass.xml`). Old `dashboard_glass_command_now` / `_next` removed.
- Build: `:plugins:main` compiles.
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

### Developer notes (2026-11-14) — done, needs field test (user OK on sketch A)
- Sketches shown in chat (A: the user's selection with MedLink, B: default without MedLink, C: 3 pills, D: all). User:
  "keep A", and the Now / Next lines in **one row: "Próx." on the left, "Agora" on the right**.
- New `glass/GlassPillLayout.kt` (plain, tested): `GlassSlot` = every pill in the fixed order (pump group: reservoir,
  cannula, battery, MedLink battery, TBR, Bas 30m; then sensor, loop, activity; then target, last bolus, IOB, last
  carbs; Tools last). `shownGlassSlots(...)` = the user's selection + MedLink-only pills when they have data + Tools.
  `layoutGlassPills(...)` = up to 3 per side column beside the ring (left first, the columns differ by at most one),
  then rows of 3.
- `StatusAgoraCard`: the hand-written columns and the bottom row are gone; one `GlassSlotPill(slot)` draws each pill
  (same title, value, color and tap / long-press as before). Rows below the ring use the same 3 columns (weights
  1 / 1.3 / 1) so the pills line up. Removed `glassPillShortTitle`, `glassPillValue`, `SIDE_COLUMN_PILLS`; the loop dot
  is `LoopStatusDot`; the repeated `100.dp` is one constant `GLASS_PILL_WIDTH` (no theme value for a pill width yet —
  tell me if it should move to the theme). Saved selection format (`GlassSelectedPills`) not changed. Catalog KDoc
  updated (placement does not depend on `TOP_GRID` / `BOTTOM_ROW` any more).
- IOB and last carbs are now small pills like the others (before: wide bottom-row boxes). Last carbs uses the shared AAPS
  carbs icon `IcCarbs` (`core/ui`, user 2026-11-14), drawn with the Glass tint and 14 dp size (new `PillIcon` for a
  Compose icon; `SidePill(iconVector = ...)`). No copy of the icon.
- Tests: `GlassPillLayoutTest` 9/9 (sketch A, 0 / 1 pill, odd / even, more than the side slots, no pill lost or doubled,
  order kept when one is removed, MedLink-only pills). `:plugins:main` compiles, no new warnings.
- **Second round (user, 2026-11-14):**
  - Pump + MedLink battery in **ONE pill** (`GlassSlot.BATTERY_PAIR`): battery icon, title `dashboard_glass_batteries_label`
    ("Pump ML" / pt "Bomba ML" / ru "Помпа ML" / pl "Pompa ML"), value from the template `dashboard_glass_two_values`
    ("3d3h · 38%"), each value in its own warning color (`GlassPill(styledValue = ...)`, an `AnnotatedString`). Without
    a MedLink battery: the normal pump battery pill. `dashboard_glass_medlink_label` removed (no user left).
  - **Target + Tools** as two half pills side by side in the width of one pill (`GlassSlot.TARGET_TOOLS`): icon and value
    only (the title does not fit), the name kept for screen readers. Tools is alone (last) when Target is not selected.
    The half pills are (100 dp − 4 dp gap) / 2 = 48 dp each, so the pair is exactly one pill wide (the user said 50).
  - The first row under the ring starts lower (`AapsSpacing.large`, was 6 dp), so it does not cover the "TIR …" text.
  - Sketch A now: left Cannula / Bomba ML / TBR, right Bas 30m / Sensor / Target+Tools, one row with Last bolus.
  - `GlassPillLayoutTest` 10/10; `:plugins:main` compiles.
- **2026-11-15 (user):** battery pill title "Pump ML%" / "Bomba ML%" / "Помпа ML%" / "Pompa ML%" (`formatted="false"`), and the
  MedLink value without "%" (the number from `levelPercent`, no string cutting).
- **Third round (user, 2026-11-14):**
  - Target back to a **normal pill** (the value was not seen in a half pill), with the AAPS target icon `IcTtManual`.
    `TARGET_TOOLS` removed. **Tools** is always its own **small pill** (icon only, 50 dp, name for screen readers), last.
  - **TIR line cut:** the cause was the ring itself: "TIR …" was drawn inside the circle at its narrow bottom, where the
    ring stroke runs. It is now just **under** the ring (the ring and the line are one column). The extra space before
    the first row is back to 6 dp (it was not the cause).
  - Sketch A now: left Cannula / Bomba ML / TBR, right Bas 30m / Sensor / Target, one row: Last bolus, Tools (small).
  - `GlassPillLayoutTest` 10/10; `:plugins:main` compiles.

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

### Developer notes (2026-11-14) — screen found; waiting for the user's choice
- Screenshot `logs/photo_2026-11-14_11-06-57.jpg`: the star "Profiles" tab of the bottom bar
  (`ui/.../main/GlassNavigationBar.kt:94`) opens `ElementType.PROFILE_MANAGEMENT` = the upstream screen
  `ui/.../profileManagement/` (`ProfileManagementScreen`, `ProfileCarouselCard`, `ProfileEditorScreen`,
  `ProfileActivationScreen`, `TimeValueList`).
- 66 string resources, no hardcoded text. **41 have no pt-BR, and all 41 are upstream Crowdin strings** (`ui` and
  `core/ui`), for example `activate_label` "Activate", `active_profile_indicator` "ACTIVE", `add_new_profile`,
  `edit_label`, `remove_label`, `unsaved_changes`, `timeshift_label`, `running`, `target_low_label` / `_high_label`.
  No fork string is missing.
- **Question:** T18 says not to edit Crowdin `values-xx` files. Options: (a) translate them on Crowdin (published,
  needs approval, comes back with an upstream merge); (b) a fork override file in the **app** module,
  `app/src/main/res/values-pt-rBR/strings_medlink_pt.xml`: app resources override library resources with the same
  name without a build error, and Crowdin does not touch that file. Suggested: (b) now, (a) later if wanted.
- **User (2026-11-14): (b) now, then (a).**
  - (b) done: `app/src/main/res/values-pt-rBR/strings_medlink_pt.xml`, the 41 pt-BR texts, with AAPS pt-BR words
    ("Perfil", "Ativar", "FSI", ...). No name is in any app `values*` file (no duplicate). Checked in the merged
    resources (`packageFullDebugResources`): "Ativar", "Remover", "Em uso", "Alterações não salvas". Only pt-BR (ru / pl
    not asked).
  - (a) waits for the user: no Crowdin CLI and no `~/.crowdin.yml` on this machine. Plan: with the token, upload the
    same 41 texts by the REST API to branch `dev` as **unapproved** translations (an AAPS reviewer approves them);
    check first which names already have a pt-BR suggestion. After Crowdin has them and an upstream merge brings them,
    delete the override lines.
  - Field test: phone in Portuguese → star "Profiles" → every text in Portuguese.

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

### Developer notes (2026-11-14) — NOT started
- Waits for B6 (the timed read is part of B6).

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

### Developer notes (2026-11-14) — done, needs field test (user answers: 3a, torch OK)
- **Wired:** `MedLinkBLE.pumpConnectionListener` (called on "ready" and on "pump no response") → the plugin reads the
  running mode (`DISCONNECTED_PUMP` = on purpose) and asks the rule. Raise → notification `MEDLINK_PUMP_UNREACHABLE`
  with **OK** / **Silence 15 min**, the AAPS full screen alarm with sound (`runAlarm`, `R.raw.alarm`), and the
  flashlight (`MedLinkTorchBlinker`: back camera, 0.5 s on / off, always off in `finally`, on OK / silence / restored,
  and after **2 min at the latest**, because the AAPS alarm has no OK callback). Restored → alarm, light and
  notification stopped, "Pump connection restored" (30 min). `stopAlarm` is called only when our alarm started.
- **New settings** (MedLink): "Alarm when the pump is not reached" (on), "Failed connections before the alarm" (4,
  1-10), "Alarm sound" (on), "Flashlight" (on). The last three show only when the alarm is on. No new permission.
- **Not done:**
  - "no answer to OK+CONN in time" is not counted yet, only "pump no response" (I did not find one clear timeout
    place; tell me if you know the log line of that case);
  - the dead `isPumpNotReachable` (always false) is not changed: making it true would turn on old "unreachable" paths in
    TBR / bolus code; that needs its own review;
  - no own sound choice: it uses the AAPS alarm sound (3a);
  - strings English only (`CLAUDE.md`).
- Build: `:app:compileFullDebugKotlin` OK; rileylink 532/532, medtronic 212/212.

Earlier notes (rule):
- **Done:** `pump/rileylink/.../medlink/connection/MedLinkConnectionAlarmRule.kt`, a plain rule (no Android). Input:
  `onFailure()`, `onSuccess()`, `onAcknowledged()` (OK), `onSilenced()` (15 min), `onTick()`. Output: `Raise(failures,
  outageStart)`, `Stop`, `Restored`, `None`. Settings are read at each call (a change works at once).
  - My choice, say if you disagree: when the user disconnects the pump on purpose (or turns the setting off), the
    count starts again from 0. So after the pump is connected again, a pump that is still not reached alarms after
    4 new failures. Otherwise an "OK" before the disconnect would keep it silent.
- Tests: `MedLinkConnectionAlarmRuleTest` 13/13 (3 → none; 4 → once; reset; restored once; on purpose → none / stops;
  setting off; threshold setting; OK → no new alarm and no restored message; new outage → again; silence → again after
  15 min only if still failing; reconnect after on-purpose disconnect → alarms again).
- **Not done yet (needs answers):**
  1. **Sound and buttons.** AAPS `uiInteraction.runAlarm` gives a full-screen alarm with sound and one OK button, but no
     "Silence 15 min". Option (a): use `runAlarm` (OK only) and add "Silence 15 min" as a notification action.
     Option (b): our own high-priority notification channel with sound and both buttons (needs a new channel and the
     sound setting). I suggest (a): it reuses the AAPS alarm path that already works with the screen off.
  2. **Torch.** `CameraManager.setTorchMode` needs no permission from API 23, so no new permission. OK to add it (blink
     0.5 s, always off in `finally` and on stop)?
  3. Wiring: count "pump no response" (`BleConnectCommand` → `MedLinkBLE.pumpConnectionError`) and find where a missing
     answer to OK+CONN times out; reset on "ready" / any pump answer; remove the dead `isPumpNotReachable` (always false).
     3 new MedLink settings (on/off, count 4, torch on/off). This is done after 1 and 2 are answered.

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

### Developer notes (2026-11-14) — done, needs a careful field test (user answers: 1 OK, 2 OK, 3 loop status only)
- **Design change agreed in chat:** no planned SMB / TBR is kept and applied later (it would be an old dose). While the
  question waits the loop runs with the limit; after either answer the loop runs again at once with fresh data.
- **Start** (`MedLinkZeroTbrRule.shouldStart`, tested): battery info = age, battery age >= `OverviewBageCritical`
  (hours since the last `PUMP_BATTERY_CHANGE`; no event = no TBR), T20 failures >= the T20 setting, T20 alarm on, not
  disconnected on purpose, once per outage. Then `pumpSync.addTemporaryBasalWithTempId` 0 U/h, type
  `EMULATED_PUMP_SUSPEND`, from the first failure of the outage, 24 h long (ended at the reconnect). State (temp id,
  start, end, reservoir before) in `MedtronicLongNonKey` → kept over an app restart.
- **Reconnect** ("ready"): `syncTemporaryBasalWithTempId` ends the record at that time. After the next status read
  (fresh reservoir) the question is posted: notification `MEDLINK_ZERO_TBR_QUESTION` "The pump was not reached from
  14:05 to 15:30 and its battery is old. AAPS stored a 0 % basal for this time. <hint> Keep the 0 % basal?" with
  **Yes, keep it** / **No, remove it**. The hint (`MedLinkZeroTbrRule.pumpRan`, tested) compares the reservoir drop with
  the scheduled basal: probably stopped / probably ran / not clear. Posted again after an app restart until answered.
- **Yes:** the record stays. **No:** `invalidateTemporaryBasalWithTempId` (if it fails, the limit stays and the user is
  asked again — never a wrong low IOB without the limit). Then the state is cleared and `Loop.invoke` runs at once.
- **Limit** from the start of the record until the answer (`zeroTbrLimitActive`): `isSMBModeEnabled` = false,
  `applyBasalConstraints` <= profile basal, `applyBasalPercentConstraints` <= 100 %, reason "Waiting for your answer
  about the 0 % basal" (loop status only, answer 3).
- **To check in the field:** that no MedLink TBR record from the loop covers the 0 % record during the outage (by the
  code MedLink writes TBR records only after the pump answers); that the status read after the reconnect does not
  change the ended record; that the loop status shows the reason.
- Tests: `MedLinkZeroTbrRuleTest` 14/14; medtronic 226/226; `:app` compiles. The plugin parts (store / end / question /
  answer / limit) have no unit test (no harness for the plugin).
- Questions before I start:
  1. The 0 % TBR is only a record in AAPS. Write it with `pumpSync.syncTemporaryBasalWithPumpId` / a temp id like the
     other MedLink TBRs, with a note? Its start is in the past (first failure). OK?
  2. "No" = invalidate the record (`persistenceLayer.invalidateTemporaryBasal`, as other removed treatments). OK?
  3. The loop limit (no SMB, no TBR > 100 % until answered): as a MedLink constraint next to the existing MedLink
     limits. Where do you want the reason text shown: only the loop status, or also a notification?
- Safety note: this item lowers IOB on purpose. If the pump in fact ran, IOB is too low → more insulin later. The
  question on reconnect and the loop limit are the guard; I will not ship one without the other.
