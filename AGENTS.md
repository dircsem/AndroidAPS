This project is a fork from android aps project and it implements a communication with veo pumps, using medlink bluetooth device.
The majority of changed classes names Starts with MedLink
Tehre are a few commands that can be used to communicate with the pump.
Veo pumps are blocked for temp basal remote commands, so i'M using temp suspensions and spaced microbolus to implement temp basals.

Change log of the MedLink bolus/stop safety work (what changed, why, tests, pending items): see `MEDLINK_CHANGES.md`. Update it when changing MedLink bolus/stop/queue code.
