/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc.swarm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Versioned, structured Swarm message protocol.
 *
 * Messages are sent as a single UTF string over the existing SwarmConnection channel.
 * The shared-token wrapper (when enabled) is handled by {@link SwarmHost} / {@link SwarmWorker}.
 */
public final class SwarmProtocol {
    private SwarmProtocol() {
    }

    public static final int VERSION = 1;
    public static final String PREFIX = "swarm2 ";

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Allowlisted action payload.
     *
     * Fields are intentionally nullable so different action types can reuse the same schema.
     */
    public record Action(int v, String type, Integer x, Integer y, Integer z, Boolean ignoreY, Boolean enabled, String[] blocks) {
    }

    public static String encode(Action action) {
        if (action == null) return null;
        return PREFIX + GSON.toJson(action);
    }

    public static Action decode(String message) {
        if (message == null) return null;
        if (!message.startsWith(PREFIX)) return null;

        String json = message.substring(PREFIX.length()).trim();
        if (json.isEmpty()) return null;

        try {
            Action action = GSON.fromJson(json, Action.class);
            if (action == null) return null;
            if (action.type() == null || action.type().isBlank()) return null;
            return action;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String stop() {
        return encode(new Action(VERSION, "stop", null, null, null, null, null, null));
    }

    public static String pause() {
        return encode(new Action(VERSION, "pause", null, null, null, null, null, null));
    }

    public static String resume() {
        return encode(new Action(VERSION, "resume", null, null, null, null, null, null));
    }

    public static String recover() {
        return encode(new Action(VERSION, "recover", null, null, null, null, null, null));
    }

    public static String safeMode(boolean enabled) {
        return encode(new Action(VERSION, "safe_mode", null, null, null, null, enabled, null));
    }

    public static String goTo(int x, int y, int z, boolean ignoreY) {
        return encode(new Action(VERSION, "goto", x, y, z, ignoreY, null, null));
    }

    public static String smartGoTo(int x, int y, int z, boolean ignoreYHint) {
        return encode(new Action(VERSION, "smart_goto", x, y, z, ignoreYHint, null, null));
    }

    public static String mine(String... blockIds) {
        return encode(new Action(VERSION, "mine", null, null, null, null, null, blockIds));
    }
}
