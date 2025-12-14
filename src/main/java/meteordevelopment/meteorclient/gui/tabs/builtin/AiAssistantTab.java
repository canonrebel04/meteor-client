/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.tabs.builtin;

import meteordevelopment.meteorclient.ai.GeminiInMinecraftBridge;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.pathing.*;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.render.prompts.BlockPosPrompt;
import meteordevelopment.meteorclient.utils.render.prompts.BlocksPrompt;
import meteordevelopment.meteorclient.utils.render.prompts.OkPrompt;
import meteordevelopment.meteorclient.utils.render.prompts.YesNoPrompt;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Minimal AI Assistant tab (offline-first).
 *
 * For now this only offers structured, allowlisted suggestions that map to existing queued goals.
 */
public class AiAssistantTab extends Tab {
    private static final int HISTORY_LIMIT = 12;

    // Model list constrained to what the user's API access supports.
    private static final String[] GEMINI_MODELS = {
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash-tts",
        "gemini-robotics-er-1.5-preview",
        "gemma-3-12b",
        "gemma-3-1b",
        "gemma-3-27b",
        "gemma-3-2b",
        "gemma-3-4b",
        "gemini-2.5-flash-native-audio-dialog"
    };

    private static String validateGeminiCommand(String cmd) {
        if (cmd == null) return "Command is required.";
        String v = cmd.trim();
        if (v.isEmpty()) return "Command is required.";
        if (!v.startsWith("/")) return "Command must start with '/' (example: /ai).";
        if (v.length() == 1) return "Command must include a name after '/'.";
        return null;
    }

    private static int indexOfGeminiModel(String model) {
        if (model == null) return -1;
        for (int i = 0; i < GEMINI_MODELS.length; i++) {
            if (GEMINI_MODELS[i].equals(model)) return i;
        }
        return -1;
    }

    private static String nextGeminiModel(String current) {
        int idx = indexOfGeminiModel(current);
        if (idx == -1) return GEMINI_MODELS[0];
        return GEMINI_MODELS[(idx + 1) % GEMINI_MODELS.length];
    }

    private enum Provider {
        LOCAL("local", "Local (offline)", "Structured suggestions only."),
        GEMINI_IN_MINECRAFT("gemini_in_minecraft", "GeminiInMinecraft (local)", "Optional (not wired here yet)."),
        EXTERNAL("external", "External (n8n/webhook)", "Optional (not wired here yet).");

        final String id;
        final String label;
        final String description;

        Provider(String id, String label, String description) {
            this.id = id;
            this.label = label;
            this.description = description;
        }

        Provider next() {
            Provider[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        static Provider fromId(String id) {
            if (id == null) return LOCAL;
            for (Provider p : values()) {
                if (p.id.equals(id)) return p;
            }
            return LOCAL;
        }
    }

    public AiAssistantTab() {
        super("AI Assistant");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new AiAssistantScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof AiAssistantScreen;
    }

    private static void addHistory(String entry) {
        if (entry == null || entry.isBlank()) return;

        var history = Config.get().aiAssistantHistory;
        history.add(0, entry);
        while (history.size() > HISTORY_LIMIT) history.remove(history.size() - 1);
        Config.get().save();
    }

    private static class AiAssistantScreen extends WindowTabScreen {
        public AiAssistantScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            Provider provider = Provider.fromId(Config.get().aiAssistantProvider);

            add(theme.label("This assistant only queues allowlisted actions.")).expandX();

            add(theme.horizontalSeparator("Provider")).expandX();

            // Status line
            String suggestionsStatus = provider == Provider.LOCAL
                ? "Suggestions: enabled"
                : "Suggestions: disabled (switch to Local to use)";
            add(theme.label("Active: " + provider.label + "  •  " + suggestionsStatus)).expandX();

            WTable providerRow = add(theme.table()).expandX().widget();
            providerRow.add(theme.label("Provider"));
            providerRow.add(theme.button(provider.label + " (click to change)")).expandCellX().widget().action = () -> {
                Provider next = provider.next();
                Config.get().aiAssistantProvider = next.id;
                Config.get().save();
                reload();
            };
            providerRow.row();
            add(theme.label(provider.description)).expandX();

            if (provider == Provider.GEMINI_IN_MINECRAFT) {
                add(theme.horizontalSeparator("Ask (GeminiInMinecraft)"))
                    .expandX();

                add(theme.label("Sends a chat command and captures the reply from chat.")).expandX();
                add(theme.label("Tip: use your provider's Q&A-mode command (avoid tool/command mode)."))
                    .expandX();

                WTable askRow = add(theme.table()).expandX().widget();

                askRow.add(theme.label("Command"));
                WTextBox cmd = askRow.add(theme.textBox(Config.get().aiAssistantGeminiCommand))
                    .expandX()
                    .minWidth(180)
                    .widget();

                askRow.row();

                var cmdError = add(theme.label("")).expandX().widget();
                cmdError.visible = false;

                add(theme.label("Models"))
                    .expandX();

                WTable modelRow = add(theme.table()).expandX().widget();
                modelRow.add(theme.label("Model"));
                var modelButton = modelRow.add(theme.button(Config.get().aiAssistantGeminiModel)).expandCellX().widget();
                modelButton.action = () -> {
                    Config.get().aiAssistantGeminiModel = nextGeminiModel(Config.get().aiAssistantGeminiModel);
                    Config.get().save();
                    reload();
                };
                modelRow.row();
                modelRow.add(theme.button("Apply Model")).widget().action = () -> {
                    String model = Config.get().aiAssistantGeminiModel;
                    if (indexOfGeminiModel(model) == -1) model = GEMINI_MODELS[0];

                    ChatUtils.sendPlayerMsg("/setupai model " + model);
                    addHistory("Gemini model: " + model);
                    reload();
                };
                modelRow.add(theme.label("Uses GeminiInMinecraft: /setupai model <name>"))
                    .expandCellX();

                cmd.action = () -> {
                    String v = cmd.get();
                    Config.get().aiAssistantGeminiCommand = v != null ? v.trim() : "";
                    Config.get().save();

                    String err = validateGeminiCommand(Config.get().aiAssistantGeminiCommand);
                    if (err == null) {
                        cmdError.visible = false;
                        cmdError.set("");
                    } else {
                        cmdError.visible = true;
                        cmdError.set(err);
                    }
                };

                askRow.add(theme.label("Question"));
                WTextBox prompt = askRow.add(theme.textBox(""))
                    .expandX()
                    .minWidth(420)
                    .widget();
                prompt.setFocused(true);

                var askError = add(theme.label("")).expandX().widget();
                askError.visible = false;

                askRow.add(theme.button("Send")).widget().action = () -> {
                    askError.visible = false;
                    askError.set("");

                    String cmdValue = Config.get().aiAssistantGeminiCommand;
                    String cmdErr = validateGeminiCommand(cmdValue);
                    if (cmdErr != null) {
                        askError.visible = true;
                        askError.set(cmdErr);
                        return;
                    }

                    if (prompt.get() == null || prompt.get().trim().isEmpty()) {
                        askError.visible = true;
                        askError.set("Type a question first.");
                        return;
                    }

                    askGemini(prompt);
                };

                // Initial validation state
                String err = validateGeminiCommand(Config.get().aiAssistantGeminiCommand);
                if (err != null) {
                    cmdError.visible = true;
                    cmdError.set(err);
                }
            } else if (provider != Provider.LOCAL) {
                add(theme.label("This provider is not implemented in this build yet.")).expandX();
            }

            add(theme.horizontalSeparator("Suggestions"))
                .expandX();

            add(theme.label("These are offline, structured actions that queue Baritone goals.")).expandX();

            boolean enabled = provider == Provider.LOCAL;

            add(theme.horizontalSeparator("Navigation")).expandX();
            WTable nav = add(theme.table()).expandX().minWidth(520).widget();
            addSuggestionRow(nav,
                "Go…",
                "Safe Smart Goto",
                "Enable Safe Mode and Smart Goto to a target block.",
                enabled,
                this::queueSafeSmartGoto
            );
            addSuggestionRow(nav,
                "Recover & Go…",
                "Recover + Safe Smart Goto",
                "Recover once, then enable Safe Mode and Smart Goto.",
                enabled,
                this::queueRecoverThenSafeSmartGoto
            );

            add(theme.horizontalSeparator("Mining")).expandX();
            WTable mining = add(theme.table()).expandX().minWidth(520).widget();
            addSuggestionRow(mining,
                "Mine…",
                "Safe Mine",
                "Enable Safe Mode and mine selected blocks.",
                enabled,
                this::queueSafeMine
            );
            addSuggestionRow(mining,
                "Recover & Mine…",
                "Recover + Safe Mine",
                "Recover once, then enable Safe Mode and mine selected blocks.",
                enabled,
                this::queueRecoverThenSafeMine
            );

            add(theme.horizontalSeparator("Emergency")).expandX();
            WTable emergency = add(theme.table()).expandX().minWidth(520).widget();
            addSuggestionRow(emergency,
                "Stop",
                "Stop Baritone",
                "Cancel Baritone pathing immediately.",
                enabled,
                this::stop
            );

            add(theme.horizontalSeparator("History" )).expandX();

            var history = Config.get().aiAssistantHistory;
            if (history.isEmpty()) {
                add(theme.label("—")).expandX();
            } else {
                for (String entry : history) {
                    add(theme.label(entry)).expandX();
                }
            }

            add(theme.table()).expandX().widget().add(theme.button("Clear History")).expandX().widget().action = () -> {
                Config.get().aiAssistantHistory.clear();
                Config.get().save();
                reload();
            };
        }

        private void addSuggestionRow(WTable table, String buttonText, String title, String desc, boolean enabled, Runnable action) {
            var button = table.add(theme.button(buttonText)).widget();
            button.action = () -> {
                if (!enabled) {
                    OkPrompt.create(theme, this)
                        .title("Provider not available")
                        .message("Switch provider to Local (offline) to use suggestions.")
                        .dontShowAgainCheckboxVisible(false)
                        .show();
                    return;
                }

                action.run();
            };

            table.add(theme.label(title)).expandCellX();
            table.add(theme.label(desc)).expandCellX();
            table.row();
        }

        private void askGemini(WTextBox prompt) {
            if (prompt == null) return;
            String userPrompt = prompt.get().trim();
            if (userPrompt.isEmpty()) return;

            String cmd = Config.get().aiAssistantGeminiCommand;
            String cmdErr = validateGeminiCommand(cmd);
            if (cmdErr != null) {
                OkPrompt.create(theme, this)
                    .title("Invalid command")
                    .message(cmdErr)
                    .dontShowAgainCheckboxVisible(false)
                    .show();
                return;
            }

            // Match the known-working manual flow: send exactly `/ai <query>`.
            // Reply capture is best-effort (chat parsing) and may pick up the next AI message.
            boolean started = GeminiInMinecraftBridge.beginRequestWithoutTag("/ai", reply -> {
                if (reply == null || reply.isBlank()) {
                    addHistory("Gemini: —");
                } else {
                    addHistory("Gemini: " + reply);

                    // Actionable hint for the common failure reported by GeminiInMinecraft in tool/command mode.
                    if (reply.contains("Request error!") && reply.contains("Status code: 400") && reply.contains("systemInstruction")) {
                        String hint = "Fix: GeminiInMinecraft is returning 400 (systemInstruction). This is inside GeminiInMinecraft; update that mod or its config. If /ai works in chat but not here, tell me the exact chat output format so I can improve capture.";
                        addHistory(hint);
                        ChatUtils.info(hint);
                    }
                }
                reload();
            });

            if (!started) {
                OkPrompt.create(theme, this)
                    .title("Busy")
                    .message("A Gemini request is already in progress.")
                    .dontShowAgainCheckboxVisible(false)
                    .show();
                return;
            }

            addHistory("Ask Gemini: " + userPrompt);
            prompt.set("");

            ChatUtils.sendPlayerMsg(cmd.trim() + " " + userPrompt);
            reload();
        }

        private BaritonePathManager getBaritoneOrWarn() {
            if (PathManagers.get() instanceof BaritonePathManager baritone) return baritone;

            OkPrompt.create(theme, this)
                .title("Baritone not active")
                .message("Select the Baritone Path Manager first.")
                .dontShowAgainCheckboxVisible(false)
                .show();
            return null;
        }

        private void stop() {
            BaritonePathManager baritone = getBaritoneOrWarn();
            if (baritone == null) return;

            baritone.stop();
            addHistory("Stop");
            reload();
        }

        private void queueSafeSmartGoto() {
            BaritonePathManager baritone = getBaritoneOrWarn();
            if (baritone == null) return;

            BlockPos initial = getCrosshairBlockPosOrPlayer();
            new BlockPosPrompt(theme, this, initial, (pos, ignoreYHint) -> {
                AutomationGoal goal = new SafeSmartGotoGoal(pos, ignoreYHint, true);
                baritone.getAgent().enqueueGoal(goal);
                addHistory(goal.explain());
                reload();
            })
                .title("Queue Safe Smart Goto")
                .setHereButtonVisible(false)
                .dontShowAgainCheckboxVisible(false)
                .show();
        }

        private void queueRecoverThenSafeSmartGoto() {
            BaritonePathManager baritone = getBaritoneOrWarn();
            if (baritone == null) return;

            BlockPos initial = getCrosshairBlockPosOrPlayer();
            new BlockPosPrompt(theme, this, initial, (pos, ignoreYHint) -> {
                AutomationGoal goal = new RecoverThenSafeSmartGotoGoal(pos, ignoreYHint, true);
                baritone.getAgent().enqueueGoal(goal);
                addHistory(goal.explain());
                reload();
            })
                .title("Queue Recover + Safe Smart Goto")
                .setHereButtonVisible(false)
                .dontShowAgainCheckboxVisible(false)
                .show();
        }

        private void queueSafeMine() {
            BaritonePathManager baritone = getBaritoneOrWarn();
            if (baritone == null) return;

            new BlocksPrompt(theme, this, blocks -> {
                if (blocks == null || blocks.length == 0) return;
                AutomationGoal goal = new SafeMineGoal(blocks, true);

                confirmAndEnqueueHighImpact(baritone, "Confirm", goal);
            })
                .title("Queue Safe Mine")
                .dontShowAgainCheckboxVisible(false)
                .show();
        }

        private void queueRecoverThenSafeMine() {
            BaritonePathManager baritone = getBaritoneOrWarn();
            if (baritone == null) return;

            new BlocksPrompt(theme, this, blocks -> {
                if (blocks == null || blocks.length == 0) return;
                AutomationGoal goal = new RecoverThenSafeMineGoal(blocks, true);

                confirmAndEnqueueHighImpact(baritone, "Confirm", goal);
            })
                .title("Queue Recover + Safe Mine")
                .dontShowAgainCheckboxVisible(false)
                .show();
        }

        private void confirmAndEnqueueHighImpact(BaritonePathManager baritone, String title, AutomationGoal goal) {
            List<BaritoneTaskAgent.Task> tasks = goal.compile();
            String tasksPreview = formatTaskPreview(tasks);

            YesNoPrompt.create(theme, this)
                .title(title)
                .message("This action can be high-impact (e.g., mining/building).")
                .message(goal.explain())
                .message("Will enqueue %d task(s).", tasks != null ? tasks.size() : 0)
                .message(tasksPreview)
                .dontShowAgainCheckboxVisible(false)
                .onYes(() -> {
                    baritone.getAgent().enqueueGoal(goal);
                    addHistory(goal.explain());
                    reload();
                })
                .show();
        }

        private static String formatTaskPreview(List<BaritoneTaskAgent.Task> tasks) {
            if (tasks == null || tasks.isEmpty()) return "Tasks: —";

            StringBuilder sb = new StringBuilder();
            sb.append("Tasks: ");

            int i = 0;
            for (BaritoneTaskAgent.Task task : tasks) {
                if (task == null) continue;
                if (i > 0) sb.append(" → ");

                sb.append(task.name());
                String detail = task.detail();
                if (detail != null && !detail.isBlank()) {
                    sb.append(" (").append(detail).append(")");
                }

                i++;
            }

            return sb.toString();
        }

        private static BlockPos getCrosshairBlockPosOrPlayer() {
            if (mc.player == null) return BlockPos.ORIGIN;
            if (mc.crosshairTarget == null) return mc.player.getBlockPos();

            return switch (mc.crosshairTarget.getType()) {
                case BLOCK -> ((net.minecraft.util.hit.BlockHitResult) mc.crosshairTarget).getBlockPos();
                default -> mc.player.getBlockPos();
            };
        }
    }
}
