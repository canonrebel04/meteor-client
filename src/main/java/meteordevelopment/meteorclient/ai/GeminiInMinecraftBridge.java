/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.ai;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.orbit.EventHandler;

import java.util.function.Consumer;

/**
 * Minimal bridge to GeminiInMinecraft via its chat output.
 *
 * This intentionally avoids tool-calling / command-execution flows and only
 * uses question-style prompts.
 */
public final class GeminiInMinecraftBridge {
    private static final GeminiInMinecraftBridge INSTANCE = new GeminiInMinecraftBridge();

    private static boolean subscribed;

    private static String pendingTag;
    private static String pendingCommandPrefix;
    private static Consumer<String> pendingCallback;

    private GeminiInMinecraftBridge() {
    }

    /**
     * Starts a single in-flight request. Returns false if another request is in progress.
     */
    public static boolean beginRequest(String tag, Consumer<String> onReply) {
        if (tag == null || tag.isBlank() || onReply == null) return false;
        if (pendingCallback != null) return false;

        if (!subscribed) {
            subscribed = true;
            MeteorClient.EVENT_BUS.subscribe(INSTANCE);
        }

        pendingTag = tag;
        pendingCommandPrefix = null;
        pendingCallback = onReply;
        return true;
    }

    /**
     * Starts a best-effort request capture without a tag. This matches the common `/ai <query>` flow.
     * The next non-empty chat message that isn't an echo of the command will be treated as the reply.
     */
    public static boolean beginRequestWithoutTag(String commandPrefix, Consumer<String> onReply) {
        if (onReply == null) return false;
        if (pendingCallback != null) return false;

        if (!subscribed) {
            subscribed = true;
            MeteorClient.EVENT_BUS.subscribe(INSTANCE);
        }

        pendingTag = null;
        pendingCommandPrefix = commandPrefix != null ? commandPrefix.trim() : null;
        pendingCallback = onReply;
        return true;
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (pendingCallback == null) return;

        String text = event.getMessage() != null ? event.getMessage().getString() : null;
        if (text == null || text.isBlank()) return;

        // If GeminiInMinecraft reports a request failure, surface it immediately.
        if (text.contains("Request error!") && text.contains("Status code")) {
            Consumer<String> cb = pendingCallback;
            pendingTag = null;
            pendingCommandPrefix = null;
            pendingCallback = null;
            cb.accept("(error) " + text);
            return;
        }

        // Tag-based capture (more reliable if the provider echoes the tag).
        if (pendingTag != null && !pendingTag.isBlank()) {
            int idx = text.indexOf(pendingTag);
            if (idx == -1) return;

            String reply = text.substring(idx + pendingTag.length()).trim();
            Consumer<String> cb = pendingCallback;

            pendingTag = null;
            pendingCommandPrefix = null;
            pendingCallback = null;

            cb.accept(reply);
            return;
        }

        // Best-effort capture (no tag): ignore obvious echo lines and take the next message.
        if (pendingCommandPrefix != null && !pendingCommandPrefix.isBlank()) {
            // Common echo formats could contain the command prefix.
            if (text.contains(pendingCommandPrefix)) return;
        }

        Consumer<String> cb = pendingCallback;
        pendingTag = null;
        pendingCommandPrefix = null;
        pendingCallback = null;
        cb.accept(text.trim());
    }
}
