/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.tabs.builtin;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.swarm.Swarm;
import meteordevelopment.meteorclient.systems.modules.misc.swarm.SwarmConnection;
import meteordevelopment.meteorclient.systems.modules.misc.swarm.SwarmProtocol;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.prompts.BlockPosPrompt;
import meteordevelopment.meteorclient.utils.render.prompts.BlocksPrompt;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class SwarmControlTab extends Tab {
    public SwarmControlTab() {
        super("Swarm Control");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new SwarmControlScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof SwarmControlScreen;
    }

    private static class SwarmControlScreen extends WindowTabScreen {
        private final boolean[] selected = new boolean[50];

        public SwarmControlScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            Swarm swarm = Modules.get().get(Swarm.class);
            if (swarm == null) {
                add(theme.label("Swarm module not found.")).expandX();
                return;
            }

            add(theme.label("Swarm is allowlisted by default. Configure trust before using Worker mode.")).expandX();

            add(theme.horizontalSeparator("Status")).expandX();
            String mode = swarm.mode.get().name();
            add(theme.label("Module: " + (swarm.isActive() ? "Active" : "Inactive") + "  •  Mode: " + mode)).expandX();

            if (swarm.isWorker()) {
                add(theme.label("Connected to: " + swarm.worker.getConnection())).expandX();
            } else if (swarm.isHost()) {
                add(theme.label("Listening on port: " + swarm.host.getConnections().length + " slots")).expandX();
            }

            add(theme.horizontalSeparator("Connections")).expandX();

            if (!swarm.isActive()) {
                add(theme.label("Enable the Swarm module to use this tab.")).expandX();
            } else if (swarm.isHost()) {
                if (swarm.requireToken.get() && swarm.token.get().isBlank()) {
                    add(theme.label("require-token is ON, but token is blank.")).expandX();
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

                    WCheckbox checkbox = workers.add(theme.checkbox(selected[i])).widget();
                    int idx = i;
                    checkbox.action = () -> selected[idx] = checkbox.checked;

                    workers.add(theme.label("Worker " + i + ": " + connection.getConnection())).expandCellX();
                    workers.row();
                }

                add(theme.horizontalSeparator("Broadcast (Allowlisted)")).expandX();

                WTable actions = add(theme.table()).expandX().widget();

                actions.add(theme.button("Stop")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.stop());
                actions.add(theme.button("Pause")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.pause());
                actions.add(theme.button("Resume")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.resume());
                actions.add(theme.button("Recover")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.recover());
                actions.row();

                actions.add(theme.button("Safe: ON")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.safeMode(true));
                actions.add(theme.button("Safe: OFF")).expandX().widget().action = () -> sendToSelected(swarm, SwarmProtocol.safeMode(false));

                actions.add(theme.button("Goto…")).expandX().widget().action = () -> {
                    BlockPos initial = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
                    new BlockPosPrompt(theme, this, initial, (pos, ignoreY) ->
                        sendToSelected(swarm, SwarmProtocol.goTo(pos.getX(), pos.getY(), pos.getZ(), ignoreY))
                    )
                        .title("Swarm Goto")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                actions.add(theme.button("Smart Goto…")).expandX().widget().action = () -> {
                    BlockPos initial = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
                    new BlockPosPrompt(theme, this, initial, (pos, ignoreYHint) ->
                        sendToSelected(swarm, SwarmProtocol.smartGoTo(pos.getX(), pos.getY(), pos.getZ(), ignoreYHint))
                    )
                        .title("Swarm Smart Goto")
                        .dontShowAgainCheckboxVisible(false)
                        .setHereButtonVisible(false)
                        .show();
                };

                actions.row();

                actions.add(theme.button("Mine…")).expandX().widget().action = () -> {
                    new BlocksPrompt(theme, this, blocks -> {
                        if (blocks == null || blocks.length == 0) return;
                        String id = Registries.BLOCK.getId(blocks[0]).toString();
                        sendToSelected(swarm, SwarmProtocol.mine(id));
                    })
                        .title("Swarm Mine")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                actions.add(theme.label(""));
                actions.add(theme.label(""));
                actions.add(theme.label(""));
                actions.row();
            } else if (swarm.isWorker()) {
                add(theme.label("Worker mode receives allowlisted actions from the host."))
                    .expandX();
                if (swarm.requireTrustedHost.get() && swarm.trustedHosts.get().isEmpty()) {
                    add(theme.label("Blocked: trusted-hosts is empty (rejecting all)."))
                        .expandX();
                }
            } else {
                add(theme.label("Swarm is active but not connected yet. Use the Swarm module widget or .swarm join."))
                    .expandX();
            }

            add(theme.horizontalSeparator("Settings")).expandX();
            add(theme.settings(swarm.settings, "")).expandX();
        }

        private void selectAllWorkers(Swarm swarm, boolean value) {
            if (swarm == null || !swarm.isHost()) return;

            SwarmConnection[] connections = swarm.host.getConnections();
            for (int i = 0; i < connections.length; i++) {
                if (connections[i] != null) selected[i] = value;
            }

            reload();
        }

        private void sendToSelected(Swarm swarm, String message) {
            if (swarm == null || !swarm.isHost()) return;
            if (message == null || message.isBlank()) return;

            boolean sent = false;
            SwarmConnection[] connections = swarm.host.getConnections();
            for (int i = 0; i < connections.length; i++) {
                if (connections[i] == null) continue;
                if (!selected[i]) continue;

                swarm.host.sendMessageTo(i, message);
                sent = true;
            }

            if (!sent) ChatUtils.warningPrefix("Swarm", "No workers selected.");
        }
    }
}
