/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.config;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Config extends System<Config> {
    public final Settings settings = new Settings();

    private final SettingGroup sgVisual = settings.createGroup("Visual");
    private final SettingGroup sgModules = settings.createGroup("Modules");
    private final SettingGroup sgChat = settings.createGroup("Chat");
    private final SettingGroup sgMisc = settings.createGroup("Misc");
    private final SettingGroup sgAutomation = settings.createGroup("Automation");

    // Visual

    public final Setting<Boolean> customFont = sgVisual.add(new BoolSetting.Builder()
        .name("custom-font")
        .description("Use a custom font.")
        .defaultValue(true)
        .build()
    );

    public final Setting<FontFace> font = sgVisual.add(new FontFaceSetting.Builder()
        .name("font")
        .description("Custom font to use.")
        .visible(customFont::get)
        .onChanged(Fonts::load)
        .build()
    );

    public final Setting<Double> rainbowSpeed = sgVisual.add(new DoubleSetting.Builder()
        .name("rainbow-speed")
        .description("The global rainbow speed.")
        .defaultValue(0.5)
        .range(0, 10)
        .sliderMax(5)
        .build()
    );

    public final Setting<Boolean> titleScreenCredits = sgVisual.add(new BoolSetting.Builder()
        .name("title-screen-credits")
        .description("Show Meteor credits on title screen")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> titleScreenSplashes = sgVisual.add(new BoolSetting.Builder()
        .name("title-screen-splashes")
        .description("Show Meteor splash texts on title screen")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> customWindowTitle = sgVisual.add(new BoolSetting.Builder()
        .name("custom-window-title")
        .description("Show custom text in the window title.")
        .defaultValue(false)
        .onModuleActivated(setting -> mc.updateWindowTitle())
        .onChanged(value -> mc.updateWindowTitle())
        .build()
    );

    public final Setting<String> customWindowTitleText = sgVisual.add(new StringSetting.Builder()
        .name("window-title-text")
        .description("The text it displays in the window title.")
        .visible(customWindowTitle::get)
        .defaultValue("Minecraft {mc_version} - {meteor.name} {meteor.version}")
        .onChanged(value -> mc.updateWindowTitle())
        .build()
    );

    public final Setting<SettingColor> friendColor = sgVisual.add(new ColorSetting.Builder()
        .name("friend-color")
        .description("The color used to show friends.")
        .defaultValue(new SettingColor(0, 255, 180))
        .build()
    );

    public final Setting<Boolean> syncListSettingWidths = sgVisual.add(new BoolSetting.Builder()
        .name("sync-list-setting-widths")
        .description("Prevents the list setting screens from moving around as you add & remove elements.")
        .defaultValue(false)
        .build()
    );

    // Modules

    public final Setting<List<Module>> hiddenModules = sgModules.add(new ModuleListSetting.Builder()
        .name("hidden-modules")
        .description("Prevent these modules from being rendered as options in the clickgui.")
        .build()
    );

    public final Setting<Integer> moduleSearchCount = sgModules.add(new IntSetting.Builder()
        .name("module-search-count")
        .description("Amount of modules and settings to be shown in the module search bar.")
        .defaultValue(8)
        .min(1).sliderMax(12)
        .build()
    );

    public final Setting<Boolean> cyberGlassModulesScreen = sgModules.add(new BoolSetting.Builder()
        .name("cyber-glass-modules-screen")
        .description("Use the opt-in Cyber-Glass Modules screen (inline quick settings).")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> moduleAliases = sgModules.add(new BoolSetting.Builder()
        .name("search-module-aliases")
        .description("Whether or not module aliases will be used in the module search bar.")
        .defaultValue(true)
        .build()
    );

    // Chat

    public final Setting<String> prefix = sgChat.add(new StringSetting.Builder()
        .name("prefix")
        .description("Prefix.")
        .defaultValue(".")
        .build()
    );

    public final Setting<Boolean> chatFeedback = sgChat.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Sends chat feedback when meteor performs certain actions.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> deleteChatFeedback = sgChat.add(new BoolSetting.Builder()
        .name("delete-chat-feedback")
        .description("Delete previous matching chat feedback to keep chat clear.")
        .visible(chatFeedback::get)
        .defaultValue(true)
        .build()
    );

    // Misc

    public final Setting<Integer> rotationHoldTicks = sgMisc.add(new IntSetting.Builder()
        .name("rotation-hold")
        .description("Hold long to hold server side rotation when not sending any packets.")
        .defaultValue(4)
        .build()
    );

    public final Setting<Boolean> useTeamColor = sgMisc.add(new BoolSetting.Builder()
        .name("use-team-color")
        .description("Uses player's team color for rendering things like esp and tracers.")
        .defaultValue(true)
        .build()
    );

    // Automation

    public final Setting<Boolean> automationPauseOnLowHealth = sgAutomation.add(new BoolSetting.Builder()
        .name("automation-pause-on-low-health")
        .description("Pause automation queue while health is low.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> automationLowHealthHpThreshold = sgAutomation.add(new DoubleSetting.Builder()
        .name("automation-low-health-hp")
        .description("Health (including absorption) at or below which automation is considered low-health.")
        .defaultValue(6.0)
        .min(0)
        .sliderMax(20)
        .visible(automationPauseOnLowHealth::get)
        .build()
    );

    public final Setting<Boolean> automationPauseOnInventoryFull = sgAutomation.add(new BoolSetting.Builder()
        .name("automation-pause-on-inventory-full")
        .description("Pause automation queue while the main inventory is full.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> automationAutoRecoverWhenStuck = sgAutomation.add(new BoolSetting.Builder()
        .name("automation-auto-recover-when-stuck")
        .description("Automatically trigger Recover once when the player becomes stuck during a task.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> automationUnreachableStartTimeoutTicks = sgAutomation.add(new IntSetting.Builder()
        .name("automation-unreachable-start-timeout-ticks")
        .description("If a task doesn't start pathing within this many ticks, mark it as unreachable.")
        .defaultValue(60)
        .min(5)
        .sliderMax(200)
        .build()
    );

    public final Setting<Integer> automationTaskEndIdleTicks = sgAutomation.add(new IntSetting.Builder()
        .name("automation-task-end-idle-ticks")
        .description("After pathing stops, wait this many idle ticks before marking the task complete.")
        .defaultValue(5)
        .min(1)
        .sliderMax(40)
        .build()
    );

    public final Setting<Integer> automationQueuePreviewLimit = sgAutomation.add(new IntSetting.Builder()
        .name("automation-queue-preview-limit")
        .description("How many queued tasks to include in the HUD/status queue preview.")
        .defaultValue(2)
        .min(0)
        .sliderMax(10)
        .build()
    );

    public List<String> dontShowAgainPrompts = new ArrayList<>();

    public List<String> aiAssistantHistory = new ArrayList<>();

    public String aiAssistantProvider = "local";

    // Command used to send prompts to GeminiInMinecraft. Different versions/configurations may
    // expose separate commands for Q&A vs. tool-calling modes.
    public String aiAssistantGeminiCommand = "/ai";

    // Gemini model to use via GeminiInMinecraft. Applied by sending `/setupai model <name>`.
    public String aiAssistantGeminiModel = "gemini-2.5-flash";

    public Config() {
        super("config");
    }

    public static Config get() {
        return Systems.get(Config.class);
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();

        tag.putString("version", MeteorClient.VERSION.toString());
        tag.put("settings", settings.toTag());
        tag.put("dontShowAgainPrompts", listToTag(dontShowAgainPrompts));
        tag.put("aiAssistantHistory", listToTag(aiAssistantHistory));
        tag.putString("aiAssistantProvider", aiAssistantProvider);
        tag.putString("aiAssistantGeminiCommand", aiAssistantGeminiCommand);
        tag.putString("aiAssistantGeminiModel", aiAssistantGeminiModel);

        return tag;
    }

    @Override
    public Config fromTag(NbtCompound tag) {
        if (tag.contains("settings")) settings.fromTag(tag.getCompoundOrEmpty("settings"));
        if (tag.contains("dontShowAgainPrompts")) dontShowAgainPrompts = listFromTag(tag, "dontShowAgainPrompts");
        if (tag.contains("aiAssistantHistory")) aiAssistantHistory = listFromTag(tag, "aiAssistantHistory");
        if (tag.contains("aiAssistantProvider")) aiAssistantProvider = tag.getString("aiAssistantProvider").orElse("local");
        if (tag.contains("aiAssistantGeminiCommand")) aiAssistantGeminiCommand = tag.getString("aiAssistantGeminiCommand").orElse("/ai");
        if (tag.contains("aiAssistantGeminiModel")) aiAssistantGeminiModel = tag.getString("aiAssistantGeminiModel").orElse("gemini-2.5-flash");

        return this;
    }

    private NbtList listToTag(List<String> list) {
        NbtList nbt = new NbtList();
        for (String item : list) nbt.add(NbtString.of(item));
        return nbt;
    }

    private List<String> listFromTag(NbtCompound tag, String key) {
        List<String> list = new ArrayList<>();
        for (NbtElement item : tag.getListOrEmpty(key)) list.add(item.asString().orElse(""));
        return list;
    }
}
