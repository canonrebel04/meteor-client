/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.tabs.builtin;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.pathing.BaritonePathManager;
import meteordevelopment.meteorclient.utils.render.prompts.BlockPosPrompt;
import meteordevelopment.meteorclient.utils.render.prompts.BlocksPrompt;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.math.BlockPos;

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
        public PathManagerScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);

            PathManagers.get().getSettings().get().onActivated();
        }

        @Override
        public void initWidgets() {
            WTextBox filter = add(theme.textBox("")).minWidth(400).expandX().widget();
            filter.setFocused(true);

            filter.action = () -> rebuild(filter);

            rebuild(filter);
        }

        private void rebuild(WTextBox filter) {
            clear();
            add(filter);

            if (PathManagers.get() instanceof BaritonePathManager baritone) {
                add(theme.horizontalSeparator("Baritone Actions")).expandX();

                WHorizontalList actions = add(theme.horizontalList()).expandX().widget();

                actions.add(theme.button("Stop")).widget().action = baritone::stop;

                var pause = actions.add(theme.button(baritone.isPaused() ? "Resume" : "Pause")).widget();
                pause.action = () -> {
                    if (baritone.isPaused()) baritone.resume();
                    else baritone.pause();

                    pause.set(baritone.isPaused() ? "Resume" : "Pause");
                };

                actions.add(theme.button("Goto…")).widget().action = () -> {
                    BlockPos initial = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
                    new BlockPosPrompt(theme, this, initial, baritone::moveTo)
                        .title("Goto")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                actions.add(theme.button("Smart Goto…")).widget().action = () -> {
                    BlockPos initial = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
                    new BlockPosPrompt(theme, this, initial, (pos, ignoreY) -> baritone.smartMoveTo(pos))
                        .title("Smart Goto")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                actions.add(theme.button("Mine…")).widget().action = () -> {
                    new BlocksPrompt(theme, this, baritone::mine)
                        .title("Mine")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                actions.add(theme.button("Recover")).widget().action = baritone::recover;

                var safeMode = actions.add(theme.button(baritone.isSafeMode() ? "Safe: ON" : "Safe: OFF")).widget();
                safeMode.action = () -> {
                    baritone.setSafeMode(!baritone.isSafeMode());
                    safeMode.set(baritone.isSafeMode() ? "Safe: ON" : "Safe: OFF");
                };

                add(theme.horizontalSeparator("Background Agent")).expandX();

                WHorizontalList agentRow = add(theme.horizontalList()).expandX().widget();

                agentRow.add(theme.button("Queue Goto…")).widget().action = () -> {
                    BlockPos initial = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
                    new BlockPosPrompt(theme, this, initial, (pos, ignoreY) -> baritone.getAgent().enqueueGoTo(pos, ignoreY))
                        .title("Queue Goto")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                agentRow.add(theme.button("Queue Mine…")).widget().action = () -> {
                    new BlocksPrompt(theme, this, baritone.getAgent()::enqueueMine)
                        .title("Queue Mine")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                };

                agentRow.add(theme.button("Next")).widget().action = baritone.getAgent()::next;
                agentRow.add(theme.button("Clear")).widget().action = baritone.getAgent()::clear;
            }

            add(theme.settings(PathManagers.get().getSettings().get(), filter.get().trim())).expandX();
        }

        @Override
        protected void onClosed() {
            PathManagers.get().getSettings().save();
        }
    }
}
