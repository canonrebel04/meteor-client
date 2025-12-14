/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.cyberglass;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class CyberGlassThemeManager {
    private final GuiTheme theme;
    private final MeteorGuiTheme meteor;

    public CyberGlassThemeManager(GuiTheme theme) {
        this.theme = theme;
        this.meteor = theme instanceof MeteorGuiTheme t ? t : null;
    }

    public boolean isSupported() {
        return meteor != null;
    }

    public Color textColor() {
        return theme.textColor();
    }

    public Color textSecondaryColor() {
        return theme.textSecondaryColor();
    }

    public Color background(boolean hovered) {
        if (meteor == null) return theme.textSecondaryColor();
        return meteor.backgroundColor.get(false, hovered);
    }

    public Color outline(boolean hovered) {
        if (meteor == null) return theme.textColor();
        return meteor.outlineColor.get(false, hovered);
    }

    public Color activeFill() {
        if (meteor == null) return theme.textColor();
        return meteor.moduleBackground.get();
    }

    public Color accent() {
        if (meteor == null) return theme.textColor();
        return meteor.accentColor.get();
    }
}
