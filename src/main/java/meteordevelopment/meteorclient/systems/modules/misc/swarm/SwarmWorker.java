/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc.swarm;

import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.pathing.BaritonePathManager;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Set;

public class SwarmWorker extends Thread {
    private Socket socket;
    public Block target;

    // Replay/duplicate guard (best-effort). Prevents accidental repeated execution
    // from retries or dup packets by rejecting identical payloads in a short window.
    private static final int RECENT_PAYLOADS_MAX = 32;
    private static final long DUPLICATE_WINDOW_MS = 2500;
    private final int[] recentPayloadHashes = new int[RECENT_PAYLOADS_MAX];
    private final long[] recentPayloadTimes = new long[RECENT_PAYLOADS_MAX];
    private int recentPayloadIndex;

    private static final Set<String> SAFE_SUBCOMMANDS = Set.of(
        "stop",
        "goto",
        "follow",
        "scatter",
        "mine",
        "infinity-miner",
        "baritone"
    );

    private static final Set<String> SAFE_ACTION_TYPES = Set.of(
        "stop",
        "pause",
        "resume",
        "recover",
        "safe_mode",
        "goto",
        "smart_goto",
        "mine"
    );

    private long rateWindowStartMs;
    private int rateCount;

    public SwarmWorker(String ip, int port) {
        try {
            socket = new Socket(ip, port);
        } catch (Exception e) {
            socket = null;
            ChatUtils.warningPrefix("Swarm", "Server not found at %s on port %s.", ip, port);
            e.printStackTrace();
        }

        if (socket != null) start();
    }

    @Override
    public void run() {
        ChatUtils.infoPrefix("Swarm", "Connected to Swarm host on at %s on port %s.", getIp(socket.getInetAddress().getHostAddress()), socket.getPort());

        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());


            while (!isInterrupted()) {
                String read = in.readUTF();

                String payload = unwrapAndValidate(read);
                if (payload == null) continue;

                if (isDuplicatePayload(payload)) {
                    ChatUtils.warningPrefix("Swarm", "Blocked message (duplicate): (highlight)%s", payload.length() > 160 ? (payload.substring(0, 160) + "…") : payload);
                    continue;
                }

                // Prefer the structured protocol when present.
                SwarmProtocol.Action action = SwarmProtocol.decode(payload);
                if (action != null) {
                    if (!rateLimitOk()) {
                        ChatUtils.warningPrefix("Swarm", "Blocked action (rate limit): (highlight)%s", action.type());
                        continue;
                    }

                    if (action.v() != SwarmProtocol.VERSION) {
                        ChatUtils.warningPrefix("Swarm", "Rejected action with unsupported version: (highlight)%s", action.v());
                        continue;
                    }

                    if (!isAllowedAction(action.type())) {
                        ChatUtils.warningPrefix("Swarm", "Blocked action (not allowlisted): (highlight)%s", action.type());
                        continue;
                    }

                    if (!executeAction(action)) {
                        ChatUtils.warningPrefix("Swarm", "Failed to execute action: (highlight)%s", action.type());
                    }

                    continue;
                }

                if (!payload.startsWith("swarm")) continue;
                if (!isAllowed(payload)) {
                    ChatUtils.warningPrefix("Swarm", "Blocked command (not allowlisted): (highlight)%s", payload);
                    continue;
                }
                if (!rateLimitOk()) {
                    ChatUtils.warningPrefix("Swarm", "Blocked command (rate limit): (highlight)%s", payload);
                    continue;
                }

                ChatUtils.infoPrefix("Swarm", "Received command: (highlight)%s", payload);

                try {
                    Commands.dispatch(payload);
                } catch (Exception e) {
                    ChatUtils.error("Error fetching command.");
                    e.printStackTrace();
                }
            }

            in.close();
        } catch (IOException e) {
            ChatUtils.errorPrefix("Swarm", "Error in connection to host.");
            e.printStackTrace();
            disconnect();
        }
    }

    public void disconnect() {
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        PathManagers.get().stop();

        ChatUtils.infoPrefix("Swarm", "Disconnected from host.");

        interrupt();
    }

    private String unwrapAndValidate(String message) {
        if (message == null) return null;

        Swarm swarm = Modules.get().get(Swarm.class);
        if (swarm == null) return message;

        if (!isTrustedHost(swarm)) {
            ChatUtils.warningPrefix("Swarm", "Rejected message from untrusted host (%s).", getConnection());
            return null;
        }

        // If message is wrapped: swarm|TOKEN|payload
        if (message.startsWith("swarm|") ) {
            int first = message.indexOf('|');
            int second = message.indexOf('|', first + 1);
            if (second > first) {
                String token = message.substring(first + 1, second);
                String payload = message.substring(second + 1);

                if (swarm.requireToken.get()) {
                    String expected = swarm.token.get();
                    if (expected == null || expected.isBlank() || !expected.equals(token)) {
                        ChatUtils.warningPrefix("Swarm", "Rejected message with invalid token.");
                        return null;
                    }
                }

                return payload;
            }
        }

        // Not wrapped.
        if (swarm.requireToken.get()) {
            ChatUtils.warningPrefix("Swarm", "Rejected message without token.");
            return null;
        }

        return message;
    }

    private boolean isTrustedHost(Swarm swarm) {
        if (swarm == null) return true;
        if (!swarm.requireTrustedHost.get()) return true;

        List<String> trusted = swarm.trustedHosts.get();
        if (trusted == null || trusted.isEmpty()) return false;

        String ip = socket != null && socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : null;
        if (ip == null || ip.isBlank()) return false;

        String normalizedIp = ip.trim();
        String normalizedLocal = getIp(normalizedIp);

        for (String entry : trusted) {
            if (entry == null) continue;
            String t = entry.trim();
            if (t.isEmpty()) continue;

            if (t.equalsIgnoreCase(normalizedIp)) return true;
            if (t.equalsIgnoreCase(normalizedLocal)) return true;

            // Common convenience: treat localhost as 127.0.0.1
            if (t.equalsIgnoreCase("localhost") && normalizedIp.equals("127.0.0.1")) return true;
        }

        return false;
    }

    private boolean isAllowed(String payload) {
        Swarm swarm = Modules.get().get(Swarm.class);
        if (swarm != null && swarm.allowUnsafeCommands.get()) return true;

        String[] parts = payload.trim().split("\\s+", 3);
        if (parts.length < 2) return false;
        return SAFE_SUBCOMMANDS.contains(parts[1]);
    }

    private boolean isAllowedAction(String type) {
        if (type == null || type.isBlank()) return false;
        return SAFE_ACTION_TYPES.contains(type);
    }

    private boolean executeAction(SwarmProtocol.Action action) {
        @Nullable BaritonePathManager baritone = getBaritone();
        if (baritone == null) return false;

        return switch (action.type()) {
            case "stop" -> {
                baritone.stop();
                yield true;
            }
            case "pause" -> {
                baritone.pause();
                yield true;
            }
            case "resume" -> {
                baritone.resume();
                yield true;
            }
            case "recover" -> {
                baritone.recover();
                yield true;
            }
            case "safe_mode" -> {
                if (action.enabled() == null) yield false;
                baritone.setSafeMode(action.enabled());
                yield true;
            }
            case "goto" -> {
                if (action.x() == null || action.y() == null || action.z() == null) yield false;
                boolean ignoreY = action.ignoreY() != null && action.ignoreY();
                baritone.moveTo(new BlockPos(action.x(), action.y(), action.z()), ignoreY);
                yield true;
            }
            case "smart_goto" -> {
                if (action.x() == null || action.y() == null || action.z() == null) yield false;
                boolean ignoreYHint = action.ignoreY() != null && action.ignoreY();
                baritone.smartMoveTo(new BlockPos(action.x(), action.y(), action.z()), ignoreYHint);
                yield true;
            }
            case "mine" -> {
                if (action.blocks() == null || action.blocks().length == 0) yield false;

                java.util.ArrayList<Block> blocks = new java.util.ArrayList<>(action.blocks().length);
                for (String id : action.blocks()) {
                    Identifier identifier = Identifier.tryParse(id);
                    if (identifier == null) continue;
                    if (!Registries.BLOCK.containsId(identifier)) continue;

                    Block block = Registries.BLOCK.get(identifier);
                    if (block != null) blocks.add(block);
                }

                if (blocks.isEmpty()) yield false;
                baritone.mine(blocks.toArray(Block[]::new));
                yield true;
            }
            default -> false;
        };
    }

    private @Nullable BaritonePathManager getBaritone() {
        if (PathManagers.get() instanceof BaritonePathManager baritone) return baritone;
        return null;
    }

    private boolean rateLimitOk() {
        Swarm swarm = Modules.get().get(Swarm.class);
        int maxPerSec = swarm != null ? swarm.workerCommandsPerSecond.get() : 10;

        long now = System.currentTimeMillis();
        if (rateWindowStartMs == 0 || now - rateWindowStartMs >= 1000) {
            rateWindowStartMs = now;
            rateCount = 0;
        }

        rateCount++;
        return rateCount <= Math.max(1, maxPerSec);
    }

    private boolean isDuplicatePayload(String payload) {
        if (payload == null) return false;

        long now = System.currentTimeMillis();
        int hash = payload.hashCode();

        for (int i = 0; i < RECENT_PAYLOADS_MAX; i++) {
            if (recentPayloadHashes[i] != hash) continue;
            long t = recentPayloadTimes[i];
            if (t <= 0) continue;
            if (now - t <= DUPLICATE_WINDOW_MS) return true;
        }

        recentPayloadHashes[recentPayloadIndex] = hash;
        recentPayloadTimes[recentPayloadIndex] = now;
        recentPayloadIndex = (recentPayloadIndex + 1) % RECENT_PAYLOADS_MAX;
        return false;
    }

    public void tick() {
        if (target == null) return;

        PathManagers.get().stop();
        PathManagers.get().mine(target);

        target = null;
    }

    public String getConnection() {
        return getIp(socket.getInetAddress().getHostAddress()) + ":" + socket.getPort();
    }

    private String getIp(String ip) {
        return ip.equals("127.0.0.1") ? "localhost" : ip;
    }
}
