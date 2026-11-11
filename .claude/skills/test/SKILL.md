---
name: test
description: Run the MedLink unit tests (rileylink, medtronic, implementation) and the app build, then report pass/fail counts and failing tests. Use after changing MedLink bolus/stop/queue code, or when the user asks to run or check the tests.
---

# Run the MedLink tests

Arguments (optional, space separated): `rileylink`, `medtronic`, `implementation`, `app`, `all`, or a test filter
such as `*MedLinkBolusTrackerTest*`. No argument = `rileylink medtronic app` (the default check after a MedLink change).

## Steps

1. Run the unit tests of each selected module from the repo root, offline:

   | module           | command                                                              |
   |------------------|----------------------------------------------------------------------|
   | `rileylink`      | `./gradlew :pump:rileylink:testFullDebugUnitTest --offline -q`        |
   | `medtronic`      | `./gradlew :pump:medtronic:testFullDebugUnitTest --offline -q`        |
   | `implementation` | `./gradlew :implementation:testFullDebugUnitTest --offline -q`        |

   With a test filter, add `--tests '<filter>'` and run only the module that contains it
   (MedLink tests live in `pump/rileylink/src/test/kotlin/app/aaps/pump/common/hw/medlink/`; `MedLinkCommandSMBBolusTest`
   is in `implementation`). Use a 10 min timeout; rileylink takes about 3 min.

2. Count the results (do not trust Gradle's exit code alone, `-q` hides the summary):

   ```
   python3 -I .claude/skills/test/count_results.py <module> [<module> ...]
   ```

   It prints `module tests failures` per module and the name and first message line of each failing test.

3. For `app`, build it: `./gradlew :app:assembleFullDebug --offline 2>&1 | grep -E "What went wrong|BUILD|^e:" -A3`.

## Known build problems (fix and retry once, then report)

- **`DexingNoClasspathTransform` ... "is located outside the root directory"**: stale jacoco transform cache from an
  old checkout path (`projects/bomba/...`). Run `./gradlew :<module>:clean --offline -q` for the module named in the
  error (e.g. `:pump:rileylink`, `:implementation`) and build again.
- **`[ksp] ComponentProcessingStep was unable to process 'app.aaps.di.AppComponent'`**: flaky incremental KSP,
  usually right after a module clean. Run the same build again without changes.
- **`HardLimitsImplTest` (minDia/maxDia) in `implementation`** fails before any MedLink change: not ours, report it as
  known and do not try to fix it.

## Report

- One line per module: `rileylink 500/500`, `medtronic 143/143`, `:app:assembleFullDebug OK`.
- Failing tests with their message. Never call a run green when a module had failures or did not run.
- Say if a retry was needed and why (cache / KSP).
- If MedLink bolus/stop/queue code changed, the change's section in `MEDLINK_CHANGES.md` should carry the new counts
  (see `AGENTS.md`); update it or say it still needs it.
