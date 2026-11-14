Status: STARTED (2026-11-07, developer)

MedlinkBle has the interface to communicate with medlink device.
Whenever it connects to the Medlink device, before connecting to the pump a connection command should be done. 
Medlink display a command confirmed for each command that has been applied. But if there is any message being displayed at the pump screene, it won't accept any command tha changes it's status (bolus, stop, start)
Today the major bugs are: 
Whenever a bolus command is dispatched sometimes it isn't applied even after a confirmed message and sometimes the bluetooth communication breaks before the command are executed. In this cases sometimes the bolus isn't applied.
Bolus > 0.2 ui have a command to check it's progress, whenever a bolus command is confirmed and the bluetooth connection drops, we need to execute the m command to check if the bolus has been released, if not we need to repeat the bolus command, if it's been delivered repeat the m command until the bolus ends.
Stop commands have to wait to the bolus to complete, but when bluetooth is disconnected the suspend command can be exceuted while a bolus is being done.
When there is temp basal of 0% the reactivate sometimes doesn't work