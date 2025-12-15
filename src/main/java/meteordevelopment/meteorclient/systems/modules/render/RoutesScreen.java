/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.waypoints.Route;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class RoutesScreen extends WindowScreen {
    public RoutesScreen(GuiTheme theme) {
        super(theme, "Routes");
    }

    @Override
    public void initWidgets() {
        WTable table = add(theme.table()).expandX().widget();

        for (Route route : Waypoints.get().routes) {
            String safety = route.isSafe() ? "" : (net.minecraft.util.Formatting.RED + " ⚠️ UNSAFE");
            table.add(theme.label(route.name + safety)).expandX();
            table.add(theme.label(String.format("Dist: %.1fm (%s)", route.getTotalDistance(), route.getEstimatedTime())));

            WButton edit = table.add(theme.button(GuiRenderer.EDIT)).widget();
            edit.action = () -> mc.setScreen(new RouteEditorScreen(theme, route, this::reload));

            var remove = table.add(theme.confirmedMinus()).widget();
            remove.action = () -> {
                Waypoints.get().routes.remove(route);
                Waypoints.get().save();
                reload();
            };

            table.row();
        }

        add(theme.horizontalSeparator()).expandX();

        WButton create = add(theme.button("Create Route")).expandX().widget();
        create.action = () -> mc.setScreen(new RouteEditorScreen(theme, null, this::reload));
    }
}
