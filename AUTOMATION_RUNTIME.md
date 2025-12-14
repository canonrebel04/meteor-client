# Automation Runtime (Meteor Baritone Integration)

This document describes the lightweight, tick-driven automation runtime used by Meteor’s Baritone integration.

Scope: this is **offline-first** and **client-thread driven** (no background threads), designed to be safe-by-default and easy to debug.

## Components

- `BaritoneTaskAgent`
  - Public API used by UI buttons to enqueue actions (e.g. Queue Goto/Mine).
  - Preserves the existing “queue semantics” while delegating execution to the scheduler.

- `BaritoneTaskScheduler`
  - Executes queued `Task` instances on the client tick.
  - Publishes state into the blackboard every tick.

- `AutomationBlackboard`
  - Shared, tick-updated snapshot of automation state for UI (and future policy decisions).
  - Intentionally small and cheap to update.

- `AutomationGoal`
  - A small, explainable goal that compiles into a finite list of queued tasks.
  - Used for “goal-oriented automation v1” without introducing a full planner.

## Task model

Tasks are “coarse” units of intent (e.g. “Goto (ignoreY=false)”, “Mine diamond_ore”).

Goals compile into tasks:

- A goal is compiled once into a finite task list.
- The resulting tasks are queued and run with normal scheduler semantics.
- This keeps goals explainable and interruptible (Clear/Next still works).

- **Instant tasks**
  - Execute immediately and complete in the same tick.

- **Non-instant tasks**
  - Start Baritone work and complete later.
  - Scheduler considers them complete after Baritone pathing has started and then later becomes idle for a short grace window.

## Scheduler semantics

- One active task at a time.
- FIFO queue (`enqueue` appends; `clear` empties; `next` cancels current).
- If Baritone is already pathing and the next task is non-instant, the scheduler waits until Baritone is idle.

## Blackboard fields (what the UI reads)

The blackboard publishes, at minimum:

- `paused` / `pathing` / `stuck`
- `safeMode`
- `inventoryUsedSlots` / `inventoryTotalSlots` (main inventory + hotbar; 36 slots)
- `inventoryFull`
- `lowHealth`
- `hazardSummary` (optional, player-local)
- `goalSummary` (optional, Baritone goal string)
- `currentTaskName` + `currentTaskDetail` (optional) + `currentTaskAgeTicks`
- `queuedTaskCount`
- `lastTaskName` + `lastTaskDetail` (optional) + `lastTaskEndedTick`
- `lastFailureReason` + `lastFailureTick`
- `blockReason` + `blockSinceTick`

## Recovery/policy hooks (current behavior)

Policy hooks are designed to be conservative: they **block** or **attempt recovery** without adding heavy heuristics or Baritone-internals coupling.

### Stuck → one-shot Recover

When transitioning into “stuck” while a non-instant task is active, the scheduler triggers a single `recover()` attempt. The Recover routine itself enforces cooldown/backoff.

### Inventory Full → block automation

If `inventoryFull` is true, the scheduler stops Baritone and re-queues the active non-instant task at the front, then returns early (no new tasks are started).

### Low Health → block automation

If `lowHealth` is true (effective health ≤ 6.0), the scheduler stops Baritone and re-queues the active non-instant task at the front, then returns early.

### Unreachable start → failure record

If a non-instant task never successfully begins Baritone pathing within a short timeout (currently 60 ticks), the scheduler stops, ends the task, and records a failure reason.

This is intentionally conservative: it detects “no path found / can’t start” without depending on Baritone internal failure types.

## UI mapping (Path Manager → Agent Status)

The Path Manager “Agent Status” block renders key fields from the blackboard.

Status priority order is:

1. Paused
2. Inventory Full
3. Low Health
4. Stuck
5. Pathing
6. Idle

The “Failure” row shows the last failure reason and how many ticks ago it occurred.

The “Blocked” row shows why automation is currently blocked (Paused / Inventory Full / Low Health) and how long it has been blocked.

The “Current” and “Last” rows may include target details when available (e.g., coords for Goto/Smart Goto, target count for Mine).

## Testing

- Automated: `./gradlew test`
- Manual checklist: see `BARITONE_TESTING.md`
