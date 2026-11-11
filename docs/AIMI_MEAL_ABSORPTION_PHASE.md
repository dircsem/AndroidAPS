# AIMI — Meal Absorption Phase (unified meal decision)

## Purpose

Single cross-tick state machine feeding **IOB surveillance**, **HTR**, **meal priority SMB**, **basal overlay**, and **JSONL export** — replaces disconnected boolean gates (`meal_priority`, `meal_rise_confirmed`, `mealLike`, surveillance bypass).

## Phase enum

| Phase | Meaning | IOB surveillance | HTR |
|-------|---------|------------------|-----|
| `NONE` | No meal absorption context | Normal | Normal |
| `FIRST_WAVE` | Fast rise, lunch/dinner window | **Bypass** | Full rise |
| `PEAK_CORRECTION` | BG>180, IOB high, Δ quiet at peak | **Bypass** | No plateau damp |
| `INTER_WAVE` | Plateau between waves (memory 120 min) | Attenuated ×0.65 | Normal |
| `SECOND_WAVE` | Re-acceleration within memory | **Bypass** (Δ≥1.2) | Floor ≥1.5 U |
| `LATE_FAT` | 2–7h post-bolus, COB≈0 | Normal | Normal |

## Belief model

```
B = clamp(0.30·π_chrono + 0.35·K + 0.25·T + 0.10·P + boosts, 0, 1)
```

- **π_chrono** : heures 11–14 → 0.85, 17–21 → 0.80, 7–10 → 0.55
- **K** : Δ, shortAvg, combinedΔ, accélération
- **T** : projectionLead, gap, gap widening
- **P** : HR modéré (+5..+18 bpm) + Δ≥1.5 ; pénalité stress aigu

Boosts : `mealIntent` +0.25, COB≥5 +0.30, `MEAL_UNDECLARED` +0.20, Ra≥2 +0.15, UAM≥0.45 +0.10

## Memory

`MealAbsorptionMemory` — fenêtre **120 min**, `waveCount`, dernier Δ/gap/bestT.

## JSONL export

Section `meal_absorption_phase` : `phase`, `belief`, `memory_active`, `wave_count`, `meal_delivery_priority`, scores π/K/T/P.

## Field replay targets (package 1780638240029)

| Event | Before | After |
|-------|--------|-------|
| Déjeuner 14:21 BG=219 Δ=-1 | SURV + HYPER_INSTALLED | `PEAK_CORRECTION`, no SURV |
| Dîner 22:06 BG=235 Δ=+2 | HTR DEEP 0.5 U | `SECOND_WAVE`, floor ≥1.5 U |
| Dîner 20:36 BG=120 Δ=+13 | SMB ~0 | `FIRST_WAVE`, meal priority |
| STRESS flip 13:31 | HTR 0.75 U | Reste meal policy (HTR 2.5) |

## Files

- `MealAbsorptionPhaseEngine.kt` — belief + phase
- `MealAbsorptionMemory.kt` — 120 min memory
- `MealAbsorptionPhaseHysteresis.kt` — 4-tick anti-flip
- `InsulinStackingStance.kt` — phase-aware surveillance
- `HyperSeverityClassifier.kt` — plateau inhibit + rise force
- `DetermineBasalAIMI2.kt` — wiring + export
