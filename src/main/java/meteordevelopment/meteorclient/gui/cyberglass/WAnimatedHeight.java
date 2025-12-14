/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.cyberglass;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;

public class WAnimatedHeight extends WWidget {
    private final WWidget content;

    public double progress;

    public WAnimatedHeight(WWidget content) {
        this.content = content;
    }

    @Override
    public void init() {
        content.parent = this;
        content.theme = theme;
        content.init();
    }

    @Override
    protected void onCalculateSize() {
        content.calculateSize();

        width = content.width;
        height = content.height * Math.max(0, progress);
    }

    @Override
    protected void onCalculateWidgetPositions() {
        content.x = x;
        content.y = y;
        content.width = width;

        // Keep content's calculated height; we clip during rendering.
        content.calculateWidgetPositions();
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (progress <= 0 || height <= 0) return;

        renderer.scissorStart(x, y, width, height);
        content.render(renderer, mouseX, mouseY, delta);
        renderer.scissorEnd();
    }

    private boolean allowEvents() {
        return progress >= 0.99;
    }

    @Override
    public boolean onMouseClicked(Click click, boolean doubled) {
        return allowEvents() && content.mouseClicked(click, doubled);
    }

    @Override
    public boolean onMouseReleased(Click click) {
        return allowEvents() && content.mouseReleased(click);
    }

    @Override
    public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
        if (allowEvents()) content.mouseMoved(mouseX, mouseY, lastMouseX, lastMouseY);
    }

    @Override
    public boolean onMouseScrolled(double amount) {
        return allowEvents() && content.mouseScrolled(amount);
    }

    @Override
    public boolean onKeyPressed(KeyInput input) {
        return allowEvents() && content.keyPressed(input);
    }

    @Override
    public boolean onKeyRepeated(KeyInput input) {
        return allowEvents() && content.keyRepeated(input);
    }

    @Override
    public boolean onCharTyped(CharInput input) {
        return allowEvents() && content.charTyped(input);
    }
}
