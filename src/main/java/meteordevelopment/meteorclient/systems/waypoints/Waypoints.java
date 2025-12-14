/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.waypoints;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.waypoints.events.WaypointAddedEvent;
import meteordevelopment.meteorclient.systems.waypoints.events.WaypointRemovedEvent;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.files.StreamUtils;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Waypoints extends System<Waypoints> implements Iterable<Waypoint> {
    private static final String PNG = ".png";

    public static final String[] BUILTIN_ICONS = { "square", "circle", "triangle", "star", "diamond", "skull" };

    public final Map<String, AbstractTexture> icons = new ConcurrentHashMap<>();

    private final List<Waypoint> waypoints = new CopyOnWriteArrayList<>();
    public final List<Route> routes = new CopyOnWriteArrayList<>();

    public Waypoints() {
        super(null);
    }

    public static Waypoints get() {
        return Systems.get(Waypoints.class);
    }

    @Override
    public void init() {
        File iconsFolder = new File(new File(MeteorClient.FOLDER, "waypoints"), "icons");
        iconsFolder.mkdirs();

        for (String builtinIcon : BUILTIN_ICONS) {
            File iconFile = new File(iconsFolder, builtinIcon + PNG);
            if (!iconFile.exists())
                copyIcon(iconFile);
        }

        File[] files = iconsFolder.listFiles();
        if (files == null)
            return;

        for (File file : files) {
            if (file.getName().endsWith(PNG)) {
                try (FileInputStream inputStream = new FileInputStream(file)) {
                    String name = Strings.CS.removeEnd(file.getName(), PNG);
                    AbstractTexture texture = new NativeImageBackedTexture(() -> name, NativeImage.read(inputStream));
                    icons.put(name, texture);
                } catch (IOException e) {
                    MeteorClient.LOG.error("Failed to read a waypoint icon", e);
                }
            }
        }
    }

    /**
     * Adds a waypoint or saves it if it already exists
     * 
     * @return {@code true} if waypoint already exists
     */
    @SuppressWarnings("deprecation")
    public boolean add(Waypoint waypoint) {
        if (waypoints.contains(waypoint)) {
            save();
            return true;
        }

        waypoints.add(waypoint);
        save();

        MeteorClient.EVENT_BUS.post(new WaypointAddedEvent(waypoint));

        return false;
    }

    @SuppressWarnings("deprecation")
    public boolean remove(Waypoint waypoint) {
        boolean removed = waypoints.remove(waypoint);
        if (removed) {
            save();
            MeteorClient.EVENT_BUS.post(new WaypointRemovedEvent(waypoint));
        }

        return removed;
    }

    public void removeAll(Collection<Waypoint> c) {
        boolean removed = waypoints.removeAll(c);
        if (removed)
            save();
    }

    public Waypoint get(String name) {
        for (Waypoint waypoint : waypoints) {
            if (waypoint.name.get().equalsIgnoreCase(name))
                return waypoint;
        }

        return null;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        load();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onGameDisconnected(GameLeftEvent event) {
        waypoints.clear();
    }

    public static boolean checkDimension(Waypoint waypoint) {
        Dimension playerDim = PlayerUtils.getDimension();
        Dimension waypointDim = waypoint.dimension.get();

        if (playerDim == waypointDim)
            return true;
        if (!waypoint.opposite.get())
            return false;

        boolean playerOpp = playerDim == Dimension.Overworld || playerDim == Dimension.Nether;
        boolean waypointOpp = waypointDim == Dimension.Overworld || waypointDim == Dimension.Nether;

        return playerOpp && waypointOpp;
    }

    @Override
    public File getFile() {
        if (!Utils.canUpdate())
            return null;
        return new File(new File(MeteorClient.FOLDER, "waypoints"), Utils.getFileWorldName() + ".nbt");
    }

    public boolean isEmpty() {
        return waypoints.isEmpty();
    }

    @Override
    public @NotNull Iterator<Waypoint> iterator() {
        return new WaypointIterator();
    }

    private void copyIcon(File file) {
        String path = "/assets/" + MeteorClient.MOD_ID + "/textures/icons/waypoints/" + file.getName();
        InputStream in = Waypoints.class.getResourceAsStream(path);

        if (in == null) {
            MeteorClient.LOG.error("Failed to read a resource: {}", path);
            return;
        }

        StreamUtils.copy(in, file);
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.put("waypoints", NbtUtils.listToTag(waypoints));
        return tag;
    }

    @Override
    public Waypoints fromTag(NbtCompound tag) {
        waypoints.clear();
        for (NbtElement waypointTag : tag.getListOrEmpty("waypoints")) {
            waypoints.add(new Waypoint(waypointTag));
        }

        return this;
    }

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Waypoint.class, new WaypointJsonAdapter())
            .setPrettyPrinting()
            .create();

    private static final File ROUTES_FILE = new File(MeteorClient.FOLDER, "routes.json");

    @Override
    public void save(File folder) {
        super.save(folder);
        saveRoutes();
    }

    @Override
    public void load(File folder) {
        super.load(folder);
        loadRoutes();
    }

    public void saveRoutes() {
        List<RouteJson> routeJsons = new ArrayList<>();
        for (Route route : routes) {
            routeJsons.add(new RouteJson(route));
        }

        try (Writer writer = new FileWriter(ROUTES_FILE)) {
            gson.toJson(routeJsons, writer);
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to save routes", e);
        }
    }

    public void loadRoutes() {
        if (!ROUTES_FILE.exists())
            return;

        try (Reader reader = new FileReader(ROUTES_FILE)) {
            List<RouteJson> routeJsons = gson.fromJson(reader, new TypeToken<List<RouteJson>>() {
            }.getType());
            routes.clear();
            if (routeJsons != null) {
                for (RouteJson json : routeJsons) {
                    Route route = json.toRoute();
                    if (route != null) {
                        routes.add(route);
                    }
                }
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to load routes", e);
        }
    }

    public Waypoint get(java.util.UUID uuid) {
        for (Waypoint w : waypoints) {
            if (w.uuid.equals(uuid))
                return w;
        }
        return null;
    }

    // Helper DTO for JSON serialization of Routes (storing UUIDs)
    private static class RouteJson {
        String name;
        boolean loop;
        int currentIndex;
        List<java.util.UUID> waypoints = new ArrayList<>();

        public RouteJson(Route route) {
            this.name = route.name;
            this.loop = route.loop;
            this.currentIndex = route.currentIndex;
            for (Waypoint w : route.waypoints) {
                this.waypoints.add(w.uuid);
            }
        }

        public Route toRoute() {
            Route route = new Route();
            route.name = this.name;
            route.loop = this.loop;
            route.currentIndex = this.currentIndex;

            for (java.util.UUID id : waypoints) {
                Waypoint w = Waypoints.get().get(id);
                if (w != null) {
                    route.add(w);
                } else {
                    MeteorClient.LOG.warn("Waypoint with UUID {} not found when loading route {}.", id, this.name);
                }
            }
            return route;
        }
    }

    public void exportWaypoints(File file) {
        try {
            if (!file.exists())
                file.createNewFile();
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(waypoints, writer);
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to export waypoints", e);
        }
    }

    public void importWaypoints(File file) {
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<List<Waypoint>>() {
            }.getType();
            List<Waypoint> imported = gson.fromJson(reader, type);

            if (imported != null) {
                int count = 0;
                for (Waypoint waypoint : imported) {
                    if (!add(waypoint))
                        count++;
                }
                MeteorClient.LOG.info("Imported " + count + " new waypoints.");
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to import waypoints", e);
        }
    }

    private final class WaypointIterator implements Iterator<Waypoint> {
        private final Iterator<Waypoint> it = waypoints.iterator();

        @Override
        public boolean hasNext() {
            return it.hasNext();
        }

        @Override
        public Waypoint next() {
            return it.next();
        }

        @Override
        public void remove() {
            it.remove();
            save();
        }
    }
}
