# Baritone UX + Smarter Behavior Testing (Meteor Client)

This file is a quick manual checklist for validating the Meteor Baritone integration.

Related docs:
- `BARITONE_BEHAVIOR_GUIDE.md` (Smart Goto / Recover / Safe Mode)
- `AUTOMATION_RUNTIME.md` (scheduler/blackboard/policy hooks)

## Setup

1. Put the built jar into your instance mods folder.
2. Launch Minecraft and open a world (creative is easiest for repeatability).
3. Open Meteor UI (Right Shift) → Path Managers → select **Baritone**.

## Buttons sanity

### Stop / Pause
- Start a `Goto…` to a nearby point.
- Press **Pause**: movement should stop quickly.
- Press **Resume**: pathing should continue.
- Press **Stop**: Baritone should cancel and stop moving.

### Smart Goto v2
- **Ignore Y behavior**:
  - Use **Smart Goto…**, toggle “Ignore Y”, set a target with very different Y.
  - Expected: it should still accept and use an XZ-only style goal.
- **Approach radius behavior**:
  - Enable **Safe Mode**.
  - Use **Smart Goto…** to target a risky area (e.g. above a ravine edge / near lava).
  - Expected: it should prefer stopping near the goal rather than forcing exact block arrival.

### Recover v2
- Make Baritone struggle (simple reproducible cases):
  - target behind a closed door,
  - target through a 1x1 gap,
  - target inside shallow water.
- Press **Recover**.
- Expected: it cancels, clears inputs, does a small reposition (backstep/jump), then re-issues the goal.

### Automation
- Queue **Queue Goto…** to 2–3 locations.
- Confirm:
  - **Next** skips current task.
  - **Clear** empties the queue and cancels current action.

### Agent Status (runtime)
- Verify that the **Agent Status** block updates live:
  - `Inventory` shows `used/36`.
  - `Blocked` shows `—` when unblocked.
  - `Failure` shows `—` when no failures occurred.

### Baritone Agent HUD
- Open the HUD editor and add **Baritone Agent**.
- Verify it updates live (Status, Objective, current task, queue preview).

### AI Assistant tab
- Open Meteor UI (Right Shift) → **AI Assistant**.
- Click a suggestion (e.g., Safe Smart Goto) and confirm it enqueues tasks.
- Optional: switch Provider to **GeminiInMinecraft (local)** and use Ask to verify a reply is captured into History.
- Inventory full behavior:
  - Fill your inventory (36/36) and enqueue a non-instant task.
  - Expected: Status becomes **Inventory Full** and automation does not start.
  - Expected: `Blocked` becomes **Inventory Full** and counts up ticks.
- Low health behavior:
  - Drop to low health and enqueue a non-instant task.
  - Expected: Status becomes **Low Health** and automation does not start.
  - Expected: `Blocked` becomes **Low Health** and counts up ticks.
- Unreachable behavior:
  - Queue a goal that cannot be started (e.g. target in unloaded/blocked area).
  - Expected: `Failure` updates to **No path found**.

## Regression checks
- UI opens/closes without errors.
- No FPS stutter when the Path Manager tab is open.
