/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.waypoints.Route;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;

public class RouteEditorScreen extends WindowScreen {
    private final Route route;
    private final boolean isNew;
    private final Runnable reload;

    public RouteEditorScreen(GuiTheme theme, Route route, Runnable reload) {
        super(theme, route == null ? "Create Route" : "Edit Route");
        this.route = route == null ? new Route() : route;
        this.isNew = route == null;
        this.reload = reload;
    }

    @Override
    public void initWidgets() {
        WTable table = add(theme.table()).expandX().widget();

        // Name
        table.add(theme.label("Name:"));
        var nameBox = table.add(theme.textBox(route.name)).expandX().widget();
        nameBox.action = () -> route.name = nameBox.get();
        table.row();

        // Loop
        table.add(theme.label("Loop:"));
        var loopCheck = table.add(theme.checkbox(route.loop)).widget();
        loopCheck.action = () -> route.loop = loopCheck.checked;
        table.row();

        add(theme.horizontalSeparator()).expandX();

        // Waypoints list
        // Use a scrollable view for the list
        var wpView = add(theme.view()).expandX().widget();

        // Add Waypoint controls
        var addGroup = wpView.add(theme.horizontalList()).expandX().widget();
        String[] names = getWaypointNames();
        String defaultName = names.length > 0 ? names[0] : null;
        meteordevelopment.meteorclient.gui.widgets.input.WDropdown<String> wpSelector = addGroup
                .add(theme.dropdown(names, defaultName)).expandX().widget();

        WButton addWp = addGroup.add(theme.button("Add")).widget();
        addWp.action = () -> {
            String name = wpSelector.get();
            if (name != null) {
                Waypoint w = Waypoints.get().get(name);
                if (w != null) {
                    route.add(w);
                    reload(); // Refresh list
                }
            }
        };

        // List existing waypoints in route
        for (Waypoint w : route.waypoints) {
            var row = wpView.add(theme.horizontalList()).expandX().widget();
            row.add(theme.label(w.name.get())).expandX();
            var remove = row.add(theme.minus()).widget();
            remove.action = () -> {
                route.remove(w);
                reload(); // Re-init widgets
            };
        }

        add(theme.horizontalSeparator()).expandX();

        // Actions
        var actions = add(theme.horizontalList()).expandX().widget();

        WButton optimize = actions.add(theme.button("Optimize (2-opt)")).expandX().widget();
        optimize.action = () -> {
            route.optimize();
            reload();
        };

        WButton save = actions.add(theme.button(isNew ? "Create" : "Save")).expandX().widget();
        save.action = () -> {
            if (isNew)
                Waypoints.get().routes.add(route);
            Waypoints.get().save();
            if (reload != null)
                reload.run();
            close();
        };
    }

    private String[] getWaypointNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Waypoint w : Waypoints.get()) {
            names.add(w.name.get());
        }
        return names.toArray(new String[0]);
    }
}
