/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render.prompts;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.input.WBlockPosEdit;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.math.BlockPos;

import java.util.function.BiConsumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BlockPosPrompt extends Prompt<BlockPosPrompt> {
    private final BlockPos initial;
    private final BiConsumer<BlockPos, Boolean> onOk;

    private boolean setHereButtonVisible = true;

    public BlockPosPrompt(GuiTheme theme, Screen parent, BlockPos initial, BiConsumer<BlockPos, Boolean> onOk) {
        super(theme, parent);
        this.initial = initial;
        this.onOk = onOk;
    }

    public BlockPosPrompt setHereButtonVisible(boolean visible) {
        this.setHereButtonVisible = visible;
        return this;
    }

    @Override
    protected void initialiseWidgets(PromptScreen screen) {
        screen.add(theme.label("Target position:")).expandX();

        WBlockPosEdit posEdit = screen.add(theme.blockPosEdit(initial, setHereButtonVisible)).expandX().widget();

        WHorizontalList opts = screen.add(theme.horizontalList()).expandX().widget();
        WCheckbox ignoreY = opts.add(theme.checkbox(false)).widget();
        opts.add(theme.label("Ignore Y (XZ goal)")).expandX();

        WButton ok = screen.list.add(theme.button("OK")).expandX().widget();
        ok.action = () -> {
            dontShowAgain(screen);
            onOk.accept(posEdit.get(), ignoreY.checked);
            mc.setScreen(parent);
        };

        WButton cancel = screen.list.add(theme.button("Cancel")).expandX().widget();
        cancel.action = () -> {
            dontShowAgain(screen);
            mc.setScreen(parent);
        };
    }
}
