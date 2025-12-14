# Smart Goto / Recover / Safe Mode (Meteor Baritone)

This guide documents what the Meteor Path Manager controls do for Baritone, with emphasis on safety and predictable behavior.

## Where to find it

- Open Meteor UI (Right Shift)
- Go to **Path Managers**
- Select **Baritone**

## Smart Goto

Smart Goto is a safer “choose the right goal type” wrapper over Baritone’s goal setting.

What it does (high level):

- If vertical movement is likely to be problematic (or you asked to ignore Y), it can choose an XZ-only goal.
- If Safe Mode is enabled and the target looks risky, it can prefer stopping near the target instead of forcing exact block arrival.

What it does *not* do:

- It does not teleport or bypass collision.
- It does not do multi-stage planning or building.

## Recover

Recover is a manual recovery routine intended to get you out of common “stuck” situations without spamming repeated actions.

Behavior:

- Cancels current Baritone activity.
- Clears inputs.
- Performs a small reposition attempt (e.g., backstep / jump) and then re-issues the goal.

Safety/backoff:

- Recover has cooldown/backoff behavior to prevent infinite recover loops if you press it repeatedly or if an automatic policy triggers it.

## Safe Mode

Safe Mode is a conservative preset intended to reduce risky navigation and destructive behavior.

Typical effects:

- More conservative movement (reduced parkour/sprint behavior).
- Reduced destructive actions (break/place/inventory interactions are disabled in Safe Mode).
- Increased avoidance behavior (e.g., mob avoidance if the Baritone settings support it).

Reversibility:

- Safe Mode is applied in a reversible way (snapshot/restore where possible).

## Agent Status (runtime)

The Path Manager “Agent Status” block reflects the automation runtime state:

- **Status**: Paused / Inventory Full / Low Health / Stuck / Pathing / Idle
- **Blocked**: current block reason (Paused / Inventory Full / Low Health)
- **Mode**: Safe / Normal
- **Current**: active task + age in ticks (may include target details)
- **Queue**: queued task count
- **Inventory**: used/36
- **Last**: last completed task + “Nt ago” (may include target details)
- **Failure**: last failure reason + “Nt ago”

Notes:

- “Inventory Full” and “Low Health” deliberately block automation and re-queue active tasks.
- “Failure: No path found” is recorded when a task never successfully starts pathing within a short timeout.
