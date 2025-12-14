/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.cyberglass;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gui.Click;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

public class WCyberGlassModuleCard extends WVerticalList {
    private final Module module;

    private CyberGlassThemeManager themeManager;

    private double titleWidth;

    private boolean expanded;

    private double hoverProgress;
    private double activeProgress;
    private double expandProgress;

    private WWidget header;
    private WAnimatedHeight settings;

    private double arrowWidth;

    public WCyberGlassModuleCard(Module module) {
        this.module = module;
        this.tooltip = module.description;
        this.spacing = 0;

        if (module.isActive()) activeProgress = 1;
    }

    @Override
    public void init() {
        super.init();

        themeManager = new CyberGlassThemeManager(theme);

        header = new WHeader();
        header.theme = theme;
        add(header).expandX();

        arrowWidth = theme.textWidth("▸");

        WWidget settingsContent = theme.settings(module.settings);
        settings = new WAnimatedHeight(settingsContent);
        settings.theme = theme;
        settings.progress = 0;
        add(settings).expandX();
    }

    @Override
    protected void onCalculateSize() {
        super.onCalculateSize();

        // Slight padding around the whole card.
        double pad = theme.scale(2);
        width += pad * 2;
        height += pad * 2;
    }

    @Override
    protected void onCalculateWidgetPositions() {
        super.onCalculateWidgetPositions();

        double pad = theme.scale(2);
        for (var cell : cells) {
            cell.x += pad;
            cell.y += pad;
            cell.width = Math.max(0, cell.width - pad * 2);
            cell.alignWidget();
        }

        x = Math.round(x);
        y = Math.round(y);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (themeManager == null) return;

        hoverProgress = CyberGlassComponent.animate(hoverProgress, mouseOver ? 1 : 0, delta, 10);
        activeProgress = CyberGlassComponent.animate(activeProgress, module.isActive() ? 1 : 0, delta, 8);
        expandProgress = CyberGlassComponent.animate(expandProgress, expanded ? 1 : 0, delta, 14);
        expandProgress = CyberGlassComponent.clamp01(expandProgress);

        double pre = settings.progress;
        settings.progress = expandProgress;
        if (settings.progress != pre) invalidate();

        Color bg = themeManager.background(mouseOver);
        Color outline = themeManager.outline(mouseOver);

        // Background
        renderer.quad(x, y, width, height, bg);

        // Active overlay
        if (activeProgress > 0) {
            renderer.setAlpha(activeProgress);
            renderer.quad(x, y, width, height, themeManager.activeFill());
            renderer.setAlpha(1);
        }

        // Accent bar
        if (activeProgress > 0) {
            renderer.setAlpha(activeProgress);
            renderer.quad(x, y, theme.scale(2), height, themeManager.accent());
            renderer.setAlpha(1);
        }

        // Outline (1px)
        renderer.quad(x, y, width, 1, outline);
        renderer.quad(x, y + height - 1, width, 1, outline);
        renderer.quad(x, y + 1, 1, height - 2, outline);
        renderer.quad(x + width - 1, y + 1, 1, height - 2, outline);
    }

    private class WHeader extends WWidget {
        @Override
        protected void onCalculateSize() {
            double pad = theme.pad();
            if (titleWidth == 0) titleWidth = theme.textWidth(module.title);

            width = pad + titleWidth + pad;
            height = pad + theme.textHeight() + pad;
        }

        @Override
        public boolean onMouseClicked(Click click, boolean doubled) {
            if (!mouseOver || doubled) return false;

            int button = click.button();
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                module.toggle();
                return true;
            }

            if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                expanded = !expanded;
                return true;
            }

            return false;
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            // Text only; card background is rendered by parent.
            double pad = theme.pad();

            renderer.text(module.title, x + pad, y + pad, themeManager.textColor(), false);

            // Expand indicator
            renderer.setAlpha(0.6);
            renderer.text(expanded ? "▾" : "▸", x + width - pad - arrowWidth, y + pad, themeManager.textSecondaryColor(), false);
            renderer.setAlpha(1);
        }
    }
}
