/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render.prompts;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.block.Block;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BlocksPrompt extends Prompt<BlocksPrompt> {
    private final Consumer<Block[]> onOk;

    public BlocksPrompt(GuiTheme theme, Screen parent, Consumer<Block[]> onOk) {
        super(theme, parent);
        this.onOk = onOk;
    }

    @Override
    protected void initialiseWidgets(PromptScreen screen) {
        screen.add(theme.label("Blocks (comma-separated ids):")).expandX();

        WTextBox blocksBox = screen.add(theme.textBox("", "minecraft:stone,minecraft:coal_ore")).expandX().widget();
        blocksBox.setFocused(true);

        WButton ok = screen.list.add(theme.button("OK")).expandX().widget();
        ok.action = () -> {
            dontShowAgain(screen);

            String raw = blocksBox.get().trim();
            if (raw.isEmpty()) {
                OkPrompt.create(theme, screen)
                    .title("Mine")
                    .message("Please enter at least one block id.")
                    .dontShowAgainCheckboxVisible(false)
                    .show();
                return;
            }

            String[] parts = raw.split(",");
            List<Block> blocks = new ArrayList<>(parts.length);
            for (String part : parts) {
                Block block = Setting.parseId(Registries.BLOCK, part);
                if (block != null) blocks.add(block);
            }

            if (blocks.isEmpty()) {
                OkPrompt.create(theme, screen)
                    .title("Mine")
                    .message("No valid blocks found. Use ids like minecraft:stone.")
                    .dontShowAgainCheckboxVisible(false)
                    .show();
                return;
            }

            onOk.accept(blocks.toArray(Block[]::new));
            mc.setScreen(parent);
        };

        WButton cancel = screen.list.add(theme.button("Cancel")).expandX().widget();
        cancel.action = () -> {
            dontShowAgain(screen);
            mc.setScreen(parent);
        };
    }
}
