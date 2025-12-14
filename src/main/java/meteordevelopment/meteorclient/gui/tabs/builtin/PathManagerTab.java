/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.tabs.builtin;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.pathing.RecoverThenSafeMineGoal;
import meteordevelopment.meteorclient.pathing.RecoverThenSafeSmartGotoGoal;
import meteordevelopment.meteorclient.pathing.SafeMineGoal;
import meteordevelopment.meteorclient.pathing.SafeSmartGotoGoal;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.pathing.BaritonePathManager;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.swarm.Swarm;
import meteordevelopment.meteorclient.systems.modules.misc.swarm.SwarmConnection;
import meteordevelopment.meteorclient.systems.modules.misc.swarm.SwarmProtocol;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.prompts.BlockPosPrompt;
import meteordevelopment.meteorclient.utils.render.prompts.BlocksPrompt;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PathManagerTab extends Tab {
    public PathManagerTab() {
        super(PathManagers.get().getName());
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new PathManagerScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof PathManagerScreen;
    }

    private static class PathManagerScreen extends WindowTabScreen {
        private final boolean[] swarmSelected = new boolean[50];
        private WTextBox filterBox;

        private BaritonePathManager baritone;

        private WLabel statusStatus;
        private WLabel statusBlocked;
        private WLabel statusMode;
        private WLabel statusHazards;
        private WLabel statusGoal;
        private WLabel statusCurrent;
        private WLabel statusQueue;
        private WLabel statusInventory;
        private WLabel statusLast;
        private WLabel statusFailure;

        private String lastRenderedStatus;
        private String lastRenderedBlocked;
        private String lastRenderedMode;
        private String lastRenderedHazards;
        private String lastRenderedGoal;
        private String lastRenderedCurrent;
        private String lastRenderedQueue;
        private String lastRenderedInventory;
        private String lastRenderedLast;
        private String lastRenderedFailure;

        public PathManagerScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);

            PathManagers.get().getSettings().get().onActivated();
        }

        @Override
        public void initWidgets() {
            filterBox = add(theme.textBox("")).minWidth(400).expandX().widget();
            filterBox.setFocused(true);

            filterBox.action = () -> rebuild(filterBox);

            rebuild(filterBox);
        }

        private void rebuild(WTextBox filter) {
            clear();
            add(filter);

            if (PathManagers.get() instanceof BaritonePathManager baritone) {
                this.baritone = baritone;
                add(theme.horizontalSeparator("Baritone Actions")).expandX();

                WTable actions = add(theme.table()).expandX().widget();

                actions.add(theme.button("Stop")).expandX().widget().action = baritone::stop;

                var pause = actions.add(theme.button(baritone.isPaused() ? "Resume" : "Pause")).expandX().widget();
                pause.action = () -> {
                    if (baritone.isPaused()) baritone.resume();
                    else baritone.pause();

                    pause.set(baritone.isPaused() ? "Resume" : "Pause");
                };

                actions.add(theme.button("Goto…")).expandX().widget().action = () -> {
                    BlockPos initial = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
                    new BlockPosPrompt(theme, this, initial, baritone::moveTo)
                        .title("Goto")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                actions.add(theme.button("Smart Goto…")).expandX().widget().action = () -> {
                    BlockPos initial = getCrosshairBlockPosOrPlayer();
                    new BlockPosPrompt(theme, this, initial, baritone::smartMoveTo)
                        .title("Smart Goto")
                        .setHereButtonVisible(false)
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                actions.row();

                actions.add(theme.button("Mine…")).expandX().widget().action = () -> {
                    new BlocksPrompt(theme, this, baritone::mine)
                        .title("Mine")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                actions.add(theme.button("Recover")).expandX().widget().action = baritone::recover;

                var safeMode = actions.add(theme.button(baritone.isSafeMode() ? "Safe: ON" : "Safe: OFF")).expandX().widget();
                safeMode.action = () -> {
                    baritone.setSafeMode(!baritone.isSafeMode());
                    safeMode.set(baritone.isSafeMode() ? "Safe: ON" : "Safe: OFF");
                };

                actions.add(theme.label("")).expandX();
                actions.row();

                add(theme.horizontalSeparator("Automation")).expandX();

                WTable agentRow = add(theme.table()).expandX().widget();

                agentRow.add(theme.button("Queue Goto…")).expandX().widget().action = () -> {
                    BlockPos initial = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
                    new BlockPosPrompt(theme, this, initial, (pos, ignoreY) -> baritone.getAgent().enqueueGoTo(pos, ignoreY))
                        .title("Queue Goto")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                agentRow.add(theme.button("Queue Mine…")).expandX().widget().action = () -> {
                    new BlocksPrompt(theme, this, baritone.getAgent()::enqueueMine)
                        .title("Queue Mine")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                agentRow.add(theme.button("Next")).expandX().widget().action = baritone.getAgent()::next;
                agentRow.add(theme.button("Clear")).expandX().widget().action = baritone.getAgent()::clear;
                agentRow.row();

                agentRow.add(theme.button("Queue Safe Smart Goto…")).expandX().widget().action = () -> {
                    BlockPos initial = getCrosshairBlockPosOrPlayer();
                    new BlockPosPrompt(theme, this, initial, (pos, ignoreYHint) -> baritone.getAgent().enqueueGoal(new SafeSmartGotoGoal(pos, ignoreYHint, true)))
                        .title("Queue Safe Smart Goto")
                        .setHereButtonVisible(false)
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                agentRow.add(theme.button("Queue Recover + Safe Smart Goto…")).expandX().widget().action = () -> {
                    BlockPos initial = getCrosshairBlockPosOrPlayer();
                    new BlockPosPrompt(theme, this, initial, (pos, ignoreYHint) -> baritone.getAgent().enqueueGoal(new RecoverThenSafeSmartGotoGoal(pos, ignoreYHint, true)))
                        .title("Queue Recover + Safe Smart Goto")
                        .setHereButtonVisible(false)
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                agentRow.add(theme.label("")).expandX();
                agentRow.add(theme.label("")).expandX();
                agentRow.row();

                agentRow.add(theme.button("Queue Safe Mine…")).expandX().widget().action = () -> {
                    new BlocksPrompt(theme, this, blocks -> {
                        if (blocks == null || blocks.length == 0) return;
                        baritone.getAgent().enqueueGoal(new SafeMineGoal(blocks, true));
                    })
                        .title("Queue Safe Mine")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                agentRow.add(theme.button("Queue Recover + Safe Mine…")).expandX().widget().action = () -> {
                    new BlocksPrompt(theme, this, blocks -> {
                        if (blocks == null || blocks.length == 0) return;
                        baritone.getAgent().enqueueGoal(new RecoverThenSafeMineGoal(blocks, true));
                    })
                        .title("Queue Recover + Safe Mine")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                agentRow.add(theme.label("")).expandX();
                agentRow.add(theme.label("")).expandX();
                agentRow.row();

                add(theme.horizontalSeparator("Macro Recorder")).expandX();

                var macro = baritone.getMacroRecorder();
                WTable macroRow = add(theme.table()).expandX().widget();

                macroRow.add(theme.button(macro.isRecording() ? "Recording…" : "Record")).expandX().widget().action = () -> {
                    macro.startNew();
                    if (filterBox != null) rebuild(filterBox);
                };

                macroRow.add(theme.button("Stop")).expandX().widget().action = () -> {
                    macro.stop();
                    if (filterBox != null) rebuild(filterBox);
                };

                macroRow.add(theme.button("Play")).expandX().widget().action = () -> macro.play(baritone.getAgent());

                macroRow.add(theme.button("Clear")).expandX().widget().action = () -> {
                    macro.clear();
                    if (filterBox != null) rebuild(filterBox);
                };
                macroRow.row();

                add(theme.label("Steps: " + macro.size() + (macro.isRecording() ? " (recording)" : ""))).expandX();

                var steps = macro.snapshot();
                int previewCount = Math.min(10, steps.size());
                if (previewCount > 0) {
                    WTable macroPreview = add(theme.table()).expandX().widget();
                    for (int i = 0; i < previewCount; i++) {
                        var step = steps.get(i);
                        if (step == null) continue;

                        String detail = step.detail();
                        String line = (i + 1) + ". " + step.name();
                        if (detail != null && !detail.isBlank()) line += " — " + detail;

                        macroPreview.add(theme.label(line)).expandCellX();
                        macroPreview.row();
                    }

                    if (steps.size() > previewCount) {
                        macroPreview.add(theme.label("… +" + (steps.size() - previewCount) + " more")).expandCellX();
                        macroPreview.row();
                    }
                }

                add(theme.horizontalSeparator("Automation Status")).expandX();

                WTable agentStatus = add(theme.table()).expandX().widget();

                agentStatus.add(theme.label("Status"));
                statusStatus = agentStatus.add(theme.label("…")).expandX().widget();
                agentStatus.row();

                agentStatus.add(theme.label("Blocked"));
                statusBlocked = agentStatus.add(theme.label("…")).expandX().widget();
                agentStatus.row();

                agentStatus.add(theme.label("Mode"));
                statusMode = agentStatus.add(theme.label("…")).expandX().widget();
                agentStatus.row();

                agentStatus.add(theme.label("Hazards"));
                statusHazards = agentStatus.add(theme.label("…")).expandX().widget();
                agentStatus.row();

                agentStatus.add(theme.label("Goal"));
                statusGoal = agentStatus.add(theme.label("…")).expandX().widget();
                agentStatus.row();

                agentStatus.add(theme.label("Current"));
                statusCurrent = agentStatus.add(theme.label("…")).expandX().widget();
                agentStatus.row();

                agentStatus.add(theme.label("Queue"));
                statusQueue = agentStatus.add(theme.label("…")).expandX().widget();
                agentStatus.row();

                agentStatus.add(theme.label("Inventory"));
                statusInventory = agentStatus.add(theme.label("…")).expandX().widget();
                agentStatus.row();

                agentStatus.add(theme.label("Last"));
                statusLast = agentStatus.add(theme.label("…")).expandX().widget();
                agentStatus.row();

                agentStatus.add(theme.label("Failure"));
                statusFailure = agentStatus.add(theme.label("…")).expandX().widget();
                agentStatus.row();

                clearRenderedCache();

                add(theme.horizontalSeparator("Swarm Control")).expandX();

                Swarm swarm = Modules.get().get(Swarm.class);
                if (swarm != null && swarm.isActive() && swarm.isHost()) {
                    if (swarm.requireToken.get() && swarm.token.get().isBlank()) {
                        add(theme.label("Swarm: require-token is ON, but token is blank.")).expandX();
                    }

                    WTable selectRow = add(theme.table()).expandX().widget();

                    selectRow.add(theme.button("Select All")).expandX().widget().action = () -> selectAllWorkers(swarm, true);
                    selectRow.add(theme.button("Select None")).expandX().widget().action = () -> selectAllWorkers(swarm, false);
                    selectRow.add(theme.label(""));
                    selectRow.add(theme.label(""));
                    selectRow.row();

                    WTable workers = add(theme.table()).expandX().widget();
                    SwarmConnection[] connections = swarm.host.getConnections();
                    for (int i = 0; i < connections.length; i++) {
                        SwarmConnection connection = connections[i];
                        if (connection == null) continue;

                        WCheckbox checkbox = workers.add(theme.checkbox(swarmSelected[i])).widget();
                        int idx = i;
                        checkbox.action = () -> swarmSelected[idx] = checkbox.checked;

                        workers.add(theme.label("Worker " + i + ": " + connection.getConnection())).expandCellX();
                        workers.row();
                    }

                    WTable swarmActions = add(theme.table()).expandX().widget();

                    swarmActions.add(theme.button("Stop")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.stop());
                    swarmActions.add(theme.button("Pause")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.pause());
                    swarmActions.add(theme.button("Resume")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.resume());
                    swarmActions.add(theme.button("Recover")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.recover());
                    swarmActions.row();

                    swarmActions.add(theme.button("Safe: ON")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.safeMode(true));
                    swarmActions.add(theme.button("Safe: OFF")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.safeMode(false));

                    swarmActions.add(theme.button("Goto…")).expandX().widget().action = () -> {
                        BlockPos initial = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
                        new BlockPosPrompt(theme, this, initial, (pos, ignoreY) -> sendToSelected(swarm, SwarmProtocol.goTo(pos.getX(), pos.getY(), pos.getZ(), ignoreY)))
                            .title("Swarm Goto")
                            .dontShowAgainCheckboxVisible(false)
                            .show();
                    };

                    swarmActions.add(theme.button("Smart Goto…")).expandX().widget().action = () -> {
                        BlockPos initial = getCrosshairBlockPosOrPlayer();
                        new BlockPosPrompt(theme, this, initial, (pos, ignoreYHint) -> sendToSelected(swarm, SwarmProtocol.smartGoTo(pos.getX(), pos.getY(), pos.getZ(), ignoreYHint)))
                            .title("Swarm Smart Goto")
                            .setHereButtonVisible(false)
                            .dontShowAgainCheckboxVisible(false)
                            .show();
                    };

                    swarmActions.row();

                    swarmActions.add(theme.button("Mine…")).expandX().widget().action = () -> {
                        new BlocksPrompt(theme, this, blocks -> {
                            if (blocks == null || blocks.length == 0) return;
                            String id = Registries.BLOCK.getId(blocks[0]).toString();
                            sendToSelected(swarm, SwarmProtocol.mine(id));
                        })
                            .title("Swarm Mine")
                            .dontShowAgainCheckboxVisible(false)
                            .show();
                    };

                    swarmActions.add(theme.label("")).expandX();
                    swarmActions.add(theme.label("")).expandX();
                    swarmActions.add(theme.label("")).expandX();
                    swarmActions.row();
                } else {
                    add(theme.label("Swarm host not active (enable Swarm module in Host mode)."))
                        .expandX();
                }
            }

            if (!(PathManagers.get() instanceof BaritonePathManager)) {
                this.baritone = null;
                this.statusStatus = null;
                this.statusBlocked = null;
                this.statusMode = null;
                this.statusHazards = null;
                this.statusGoal = null;
                this.statusCurrent = null;
                this.statusQueue = null;
                this.statusInventory = null;
                this.statusLast = null;
                this.statusFailure = null;
                clearRenderedCache();
            }

            add(theme.settings(PathManagers.get().getSettings().get(), filter.get().trim())).expandX();
        }

        @Override
        protected void onRenderBefore(DrawContext drawContext, float delta) {
            if (baritone == null) return;
            if (statusStatus == null || statusBlocked == null || statusMode == null || statusHazards == null || statusGoal == null || statusCurrent == null || statusQueue == null || statusInventory == null || statusLast == null || statusFailure == null) return;

            var bb = baritone.getAutomationBlackboard();

            String statusText = bb.isPaused() ? "Paused" : (bb.isInventoryFull() ? "Inventory Full" : (bb.isLowHealth() ? "Low Health" : (bb.isStuck() ? "Stuck" : (bb.isPathing() ? "Pathing" : "Idle"))));

            String blockReason = bb.getBlockReason();
            String blockedText;
            if (blockReason == null) {
                blockedText = "—";
            } else {
                int since = bb.getBlockSinceTick();
                blockedText = since > 0 && bb.getTick() >= since
                    ? blockReason + " (" + (bb.getTick() - since) + "t)"
                    : blockReason;
            }
            String modeText = bb.isSafeMode() ? "Safe" : "Normal";

            String hazards = bb.getHazardSummary();
            String hazardsText = (hazards == null) ? "—" : hazards;

            String goal = bb.getGoalSummary();
            String goalText = (goal == null) ? "—" : goal;

            String currentName = bb.getCurrentTaskName();
            String currentDetail = bb.getCurrentTaskDetail();
            String currentText = currentName != null
                ? (currentDetail != null && !currentDetail.isBlank()
                    ? currentName + ": " + currentDetail + " (" + bb.getCurrentTaskAgeTicks() + "t)"
                    : currentName + " (" + bb.getCurrentTaskAgeTicks() + "t)")
                : "Idle";

            String queueText = String.valueOf(bb.getQueuedTaskCount());

            String inventoryText;
            int invTotal = bb.getInventoryTotalSlots();
            if (invTotal <= 0) {
                inventoryText = "—";
            } else {
                inventoryText = bb.getInventoryUsedSlots() + "/" + invTotal;
            }

            String lastName = bb.getLastTaskName();
            String lastDetail = bb.getLastTaskDetail();
            String lastText;
            if (lastName == null) {
                lastText = "—";
            } else {
                String base = (lastDetail != null && !lastDetail.isBlank()) ? (lastName + ": " + lastDetail) : lastName;
                int ended = bb.getLastTaskEndedTick();
                if (ended > 0 && bb.getTick() >= ended) {
                    lastText = base + " (" + (bb.getTick() - ended) + "t ago)";
                } else {
                    lastText = base;
                }
            }

            String failureReason = bb.getLastFailureReason();
            String failureText;
            if (failureReason == null) {
                failureText = "—";
            } else {
                int failedAt = bb.getLastFailureTick();
                if (failedAt > 0 && bb.getTick() >= failedAt) {
                    failureText = failureReason + " (" + (bb.getTick() - failedAt) + "t ago)";
                } else {
                    failureText = failureReason;
                }
            }

            if (!statusText.equals(lastRenderedStatus)) {
                statusStatus.set(statusText);
                lastRenderedStatus = statusText;
            }

            if (!blockedText.equals(lastRenderedBlocked)) {
                statusBlocked.set(blockedText);
                lastRenderedBlocked = blockedText;
            }

            if (!modeText.equals(lastRenderedMode)) {
                statusMode.set(modeText);
                lastRenderedMode = modeText;
            }

            if (!hazardsText.equals(lastRenderedHazards)) {
                statusHazards.set(hazardsText);
                lastRenderedHazards = hazardsText;
            }

            if (!goalText.equals(lastRenderedGoal)) {
                statusGoal.set(goalText);
                lastRenderedGoal = goalText;
            }

            if (!currentText.equals(lastRenderedCurrent)) {
                statusCurrent.set(currentText);
                lastRenderedCurrent = currentText;
            }

            if (!queueText.equals(lastRenderedQueue)) {
                statusQueue.set(queueText);
                lastRenderedQueue = queueText;
            }

            if (!inventoryText.equals(lastRenderedInventory)) {
                statusInventory.set(inventoryText);
                lastRenderedInventory = inventoryText;
            }

            if (!lastText.equals(lastRenderedLast)) {
                statusLast.set(lastText);
                lastRenderedLast = lastText;
            }

            if (!failureText.equals(lastRenderedFailure)) {
                statusFailure.set(failureText);
                lastRenderedFailure = failureText;
            }
        }

        private void clearRenderedCache() {
            lastRenderedStatus = null;
            lastRenderedBlocked = null;
            lastRenderedMode = null;
            lastRenderedHazards = null;
            lastRenderedGoal = null;
            lastRenderedCurrent = null;
            lastRenderedQueue = null;
            lastRenderedInventory = null;
            lastRenderedLast = null;
            lastRenderedFailure = null;
        }

        private void selectAllWorkers(Swarm swarm, boolean selected) {
            if (swarm == null || !swarm.isHost()) return;

            SwarmConnection[] connections = swarm.host.getConnections();
            for (int i = 0; i < connections.length; i++) {
                if (connections[i] != null) swarmSelected[i] = selected;
            }

            if (filterBox != null) rebuild(filterBox);
        }

        private void sendToSelected(Swarm swarm, String command) {
            if (swarm == null || !swarm.isHost()) return;
            if (command == null || command.isBlank()) return;

            boolean sent = false;
            SwarmConnection[] connections = swarm.host.getConnections();
            for (int i = 0; i < connections.length; i++) {
                if (connections[i] == null) continue;
                if (!swarmSelected[i]) continue;

                swarm.host.sendMessageTo(i, command);
                sent = true;
            }

            if (!sent) ChatUtils.warningPrefix("Swarm", "No workers selected.");
        }

        private static BlockPos getCrosshairBlockPosOrPlayer() {
            BlockPos far = raycastBlockPos(256.0);
            if (far != null) return far;

            if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) return ((BlockHitResult) mc.crosshairTarget).getBlockPos();
            return mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
        }

        private static BlockPos raycastBlockPos(double distance) {
            if (mc.world == null || mc.player == null) return null;

            Vec3d start = mc.player.getEyePos();
            Vec3d end = start.add(mc.player.getRotationVec(1f).multiply(distance));

            BlockHitResult hit = mc.world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player
            ));

            return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
        }

        @Override
        protected void onClosed() {
            PathManagers.get().getSettings().save();
        }
    }
}
