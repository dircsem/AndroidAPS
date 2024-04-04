package app.aaps.database.persistence.converters

import app.aaps.core.data.ue.Sources
import app.aaps.database.entities.UserEntry

fun UserEntry.Sources.fromDb(): Sources =
    when (this) {
        UserEntry.Sources.TreatmentDialog     -> Sources.TreatmentDialog
        UserEntry.Sources.InsulinDialog       -> Sources.InsulinDialog
        UserEntry.Sources.CarbDialog          -> Sources.CarbDialog
        UserEntry.Sources.WizardDialog        -> Sources.WizardDialog
        UserEntry.Sources.QuickWizard         -> Sources.QuickWizard
        UserEntry.Sources.ExtendedBolusDialog -> Sources.ExtendedBolusDialog
        UserEntry.Sources.TTDialog            -> Sources.TTDialog
        UserEntry.Sources.ProfileSwitchDialog -> Sources.ProfileSwitchDialog
        UserEntry.Sources.LoopDialog          -> Sources.LoopDialog
        UserEntry.Sources.TempBasalDialog     -> Sources.TempBasalDialog
        UserEntry.Sources.CalibrationDialog   -> Sources.CalibrationDialog
        UserEntry.Sources.FillDialog          -> Sources.FillDialog
        UserEntry.Sources.BgCheck             -> Sources.BgCheck
        UserEntry.Sources.SensorInsert        -> Sources.SensorInsert
        UserEntry.Sources.BatteryChange       -> Sources.BatteryChange
        UserEntry.Sources.Note                -> Sources.Note
        UserEntry.Sources.Exercise            -> Sources.Exercise
        UserEntry.Sources.Question            -> Sources.Question
        UserEntry.Sources.Announcement        -> Sources.Announcement
        UserEntry.Sources.Actions             -> Sources.Actions
        UserEntry.Sources.Automation          -> Sources.Automation
        UserEntry.Sources.Autotune            -> Sources.Autotune
        UserEntry.Sources.BG                  -> Sources.BG
        UserEntry.Sources.Aidex               -> Sources.Aidex
        UserEntry.Sources.Dexcom              -> Sources.Dexcom
        UserEntry.Sources.Eversense           -> Sources.Eversense
        UserEntry.Sources.Glimp               -> Sources.Glimp
        UserEntry.Sources.MM640g              -> Sources.MM640g
        UserEntry.Sources.NSClientSource      -> Sources.NSClientSource
        UserEntry.Sources.PocTech             -> Sources.PocTech
        UserEntry.Sources.Tomato              -> Sources.Tomato
        UserEntry.Sources.Glunovo             -> Sources.Glunovo
        UserEntry.Sources.Intelligo           -> Sources.Intelligo
        UserEntry.Sources.Xdrip               -> Sources.Xdrip
        UserEntry.Sources.LocalProfile        -> Sources.LocalProfile
        UserEntry.Sources.Loop                -> Sources.Loop
        UserEntry.Sources.Maintenance         -> Sources.Maintenance
        UserEntry.Sources.NSClient            -> Sources.NSClient
        UserEntry.Sources.NSProfile           -> Sources.NSProfile
        UserEntry.Sources.Objectives          -> Sources.Objectives
        UserEntry.Sources.Pump                -> Sources.Pump
        UserEntry.Sources.Dana                -> Sources.Dana
        UserEntry.Sources.DanaR               -> Sources.DanaR
        UserEntry.Sources.DanaRC              -> Sources.DanaRC
        UserEntry.Sources.DanaRv2             -> Sources.DanaRv2
        UserEntry.Sources.DanaRS              -> Sources.DanaRS
        UserEntry.Sources.DanaI               -> Sources.DanaI
        UserEntry.Sources.DiaconnG8           -> Sources.DiaconnG8
        UserEntry.Sources.Insight             -> Sources.Insight
        UserEntry.Sources.Combo               -> Sources.Combo
        UserEntry.Sources.Medtronic           -> Sources.Medtronic
        UserEntry.Sources.Omnipod             -> Sources.Omnipod
        UserEntry.Sources.OmnipodEros         -> Sources.OmnipodEros
        UserEntry.Sources.OmnipodDash         -> Sources.OmnipodDash
        UserEntry.Sources.EOPatch2            -> Sources.EOPatch2
        UserEntry.Sources.Medtrum             -> Sources.Medtrum
        UserEntry.Sources.MDI                 -> Sources.MDI
        UserEntry.Sources.VirtualPump         -> Sources.VirtualPump
        UserEntry.Sources.Random              -> Sources.Random
        UserEntry.Sources.SMS                 -> Sources.SMS
        UserEntry.Sources.Treatments          -> Sources.Treatments
        UserEntry.Sources.Wear                -> Sources.Wear
        UserEntry.Sources.Food                -> Sources.Food
        UserEntry.Sources.ConfigBuilder       -> Sources.ConfigBuilder
        UserEntry.Sources.Overview            -> Sources.Overview
        UserEntry.Sources.Stats               -> Sources.Stats
        UserEntry.Sources.Aaps                -> Sources.Aaps
        UserEntry.Sources.BgFragment          -> Sources.BgFragment
        UserEntry.Sources.Garmin              -> Sources.Garmin
        UserEntry.Sources.Unknown             -> Sources.Unknown
        UserEntry.Sources.Enlite              -> Sources.Enlite
    }

fun Sources.toDb(): UserEntry.Sources =
    when (this) {
        Sources.TreatmentDialog     -> UserEntry.Sources.TreatmentDialog
        Sources.InsulinDialog       -> UserEntry.Sources.InsulinDialog
        Sources.CarbDialog          -> UserEntry.Sources.CarbDialog
        Sources.WizardDialog        -> UserEntry.Sources.WizardDialog
        Sources.QuickWizard         -> UserEntry.Sources.QuickWizard
        Sources.ExtendedBolusDialog -> UserEntry.Sources.ExtendedBolusDialog
        Sources.TTDialog            -> UserEntry.Sources.TTDialog
        Sources.ProfileSwitchDialog -> UserEntry.Sources.ProfileSwitchDialog
        Sources.LoopDialog          -> UserEntry.Sources.LoopDialog
        Sources.TempBasalDialog     -> UserEntry.Sources.TempBasalDialog
        Sources.CalibrationDialog   -> UserEntry.Sources.CalibrationDialog
        Sources.FillDialog          -> UserEntry.Sources.FillDialog
        Sources.BgCheck             -> UserEntry.Sources.BgCheck
        Sources.SensorInsert        -> UserEntry.Sources.SensorInsert
        Sources.BatteryChange       -> UserEntry.Sources.BatteryChange
        Sources.Note                -> UserEntry.Sources.Note
        Sources.Exercise            -> UserEntry.Sources.Exercise
        Sources.Question            -> UserEntry.Sources.Question
        Sources.Announcement        -> UserEntry.Sources.Announcement
        Sources.Actions             -> UserEntry.Sources.Actions
        Sources.Automation          -> UserEntry.Sources.Automation
        Sources.Autotune            -> UserEntry.Sources.Autotune
        Sources.BG                  -> UserEntry.Sources.BG
        Sources.Aidex               -> UserEntry.Sources.Aidex
        Sources.Dexcom              -> UserEntry.Sources.Dexcom
        Sources.Eversense           -> UserEntry.Sources.Eversense
        Sources.Glimp               -> UserEntry.Sources.Glimp
        Sources.MM640g              -> UserEntry.Sources.MM640g
        Sources.NSClientSource      -> UserEntry.Sources.NSClientSource
        Sources.PocTech             -> UserEntry.Sources.PocTech
        Sources.Tomato              -> UserEntry.Sources.Tomato
        Sources.Glunovo             -> UserEntry.Sources.Glunovo
        Sources.Intelligo           -> UserEntry.Sources.Intelligo
        Sources.Xdrip               -> UserEntry.Sources.Xdrip
        Sources.LocalProfile        -> UserEntry.Sources.LocalProfile
        Sources.Loop                -> UserEntry.Sources.Loop
        Sources.Maintenance         -> UserEntry.Sources.Maintenance
        Sources.NSClient            -> UserEntry.Sources.NSClient
        Sources.NSProfile           -> UserEntry.Sources.NSProfile
        Sources.Objectives          -> UserEntry.Sources.Objectives
        Sources.Pump                -> UserEntry.Sources.Pump
        Sources.Dana                -> UserEntry.Sources.Dana
        Sources.DanaR               -> UserEntry.Sources.DanaR
        Sources.DanaRC              -> UserEntry.Sources.DanaRC
        Sources.DanaRv2             -> UserEntry.Sources.DanaRv2
        Sources.DanaRS              -> UserEntry.Sources.DanaRS
        Sources.DanaI               -> UserEntry.Sources.DanaI
        Sources.DiaconnG8           -> UserEntry.Sources.DiaconnG8
        Sources.Insight             -> UserEntry.Sources.Insight
        Sources.Combo               -> UserEntry.Sources.Combo
        Sources.Medtronic           -> UserEntry.Sources.Medtronic
        Sources.Omnipod             -> UserEntry.Sources.Omnipod
        Sources.OmnipodEros         -> UserEntry.Sources.OmnipodEros
        Sources.OmnipodDash         -> UserEntry.Sources.OmnipodDash
        Sources.EOPatch2            -> UserEntry.Sources.EOPatch2
        Sources.Medtrum             -> UserEntry.Sources.Medtrum
        Sources.MDI                 -> UserEntry.Sources.MDI
        Sources.VirtualPump         -> UserEntry.Sources.VirtualPump
        Sources.Random              -> UserEntry.Sources.Random
        Sources.SMS                 -> UserEntry.Sources.SMS
        Sources.Treatments          -> UserEntry.Sources.Treatments
        Sources.Wear                -> UserEntry.Sources.Wear
        Sources.Food                -> UserEntry.Sources.Food
        Sources.ConfigBuilder       -> UserEntry.Sources.ConfigBuilder
        Sources.Overview            -> UserEntry.Sources.Overview
        Sources.Stats               -> UserEntry.Sources.Stats
        Sources.Aaps                -> UserEntry.Sources.Aaps
        Sources.BgFragment          -> UserEntry.Sources.BgFragment
        Sources.Garmin              -> UserEntry.Sources.Garmin
        Sources.Unknown             -> UserEntry.Sources.Unknown
        Sources.Enlite              -> UserEntry.Sources.Enlite
    }

