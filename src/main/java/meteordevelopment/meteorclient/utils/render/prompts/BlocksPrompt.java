/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render.prompts;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.block.Block;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BlocksPrompt extends Prompt<BlocksPrompt> {
    private final Consumer<Block[]> onOk;

    private static final int MAX_RESULTS = 240;

    private static List<BlockEntry> allEntries;

    private record BlockEntry(Block block, Identifier id, String displayName, String searchKey, ItemStack icon) {
    }

    public BlocksPrompt(GuiTheme theme, Screen parent, Consumer<Block[]> onOk) {
        super(theme, parent);
        this.onOk = onOk;
    }

    @Override
    protected void initialiseWidgets(PromptScreen screen) {
        ensureEntries();

        screen.add(theme.label("Search blocks:")).expandX();
        WTextBox search = screen.add(theme.textBox("", "diamond ore")).expandX().widget();
        search.setFocused(true);

        WLabel selectedLabel = screen.add(theme.label("Selected: 0")).expandX().widget();

        WView resultsView = screen.add(theme.view()).expandX().widget();
        // maxHeight is already in pixels; don't apply theme.scale() again.
        resultsView.maxHeight = 260;

        Set<Block> selected = new HashSet<>();

        Runnable refreshSelectedLabel = () -> selectedLabel.set("Selected: " + selected.size());
        Runnable refreshResults = () -> {
            resultsView.clear();

            String q = normalizeQuery(search.get());
            int shown = 0;
            for (BlockEntry entry : allEntries) {
                if (shown >= MAX_RESULTS) break;

                if (!q.isEmpty() && !entry.searchKey.contains(q)) continue;

                addResultRow(resultsView, entry, selected, refreshSelectedLabel);
                shown++;
            }

            if (shown == 0) {
                resultsView.add(theme.label("No matches."));
            }

            refreshSelectedLabel.run();
        };

        search.action = refreshResults;
        refreshResults.run();

        WButton ok = screen.list.add(theme.button("OK")).expandX().widget();
        ok.action = () -> {
            dontShowAgain(screen);

            if (selected.isEmpty()) {
                OkPrompt.create(theme, screen)
                    .title("Mine")
                    .message("Pick at least one block.")
                    .dontShowAgainCheckboxVisible(false)
                    .show();
                return;
            }

            Block[] blocks = selected.toArray(Block[]::new);

            // Give immediate feedback so it's obvious something happened.
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Block b : blocks) {
                if (b == null) continue;
                Identifier id = Registries.BLOCK.getId(b);
                if (id == null) continue;
                if (shown > 0) sb.append(", ");
                sb.append(id);
                shown++;
                if (shown >= 6) break;
            }
            ChatUtils.infoPrefix("Baritone", "Mine targets: %s%s", sb.toString(), blocks.length > 6 ? "…" : "");

            try {
                onOk.accept(blocks);
            } catch (Throwable t) {
                OkPrompt.create(theme, screen)
                    .title("Mine")
                    .message("Failed to start mining. Check logs for details.")
                    .dontShowAgainCheckboxVisible(false)
                    .show();
                return;
            }
            mc.setScreen(parent);
        };

        WButton cancel = screen.list.add(theme.button("Cancel")).expandX().widget();
        cancel.action = () -> {
            dontShowAgain(screen);
            mc.setScreen(parent);
        };
    }

    private static void addResultRow(WView view, BlockEntry entry, Set<Block> selected, Runnable refreshSelectedLabel) {
        WTable row = view.add(view.theme.table()).expandX().widget();

        WCheckbox cb = row.add(view.theme.checkbox(selected.contains(entry.block))).widget();
        cb.action = () -> {
            if (cb.checked) selected.add(entry.block);
            else selected.remove(entry.block);
            refreshSelectedLabel.run();
        };

        row.add(view.theme.item(entry.icon));

        WHorizontalList labels = row.add(view.theme.horizontalList()).expandCellX().widget();
        WLabel name = labels.add(view.theme.label(entry.displayName)).widget();
        WLabel id = labels.add(view.theme.label(" (" + entry.id + ")")).widget();

        Runnable toggle = () -> {
            cb.checked = !cb.checked;
            if (cb.checked) selected.add(entry.block);
            else selected.remove(entry.block);
            refreshSelectedLabel.run();
        };

        name.action = toggle;
        id.action = toggle;
    }

    private static void ensureEntries() {
        if (allEntries != null) return;

        List<BlockEntry> entries = new ArrayList<>();
        for (Identifier id : Registries.BLOCK.getIds()) {
            Block block = Registries.BLOCK.get(id);
            if (block == null) continue;

            Item item = block.asItem();
            if (item == null || item == Items.AIR) continue;

            ItemStack icon = new ItemStack(item);
            String display = icon.getName().getString();
            if (display == null || display.isBlank()) display = id.getPath();

            String searchKey = (normalizeQuery(display) + " " + normalizeQuery(id.getPath()) + " " + normalizeQuery(id.toString())).trim();
            entries.add(new BlockEntry(block, id, display, searchKey, icon));
        }

        allEntries = entries;
    }

    private static String normalizeQuery(String s) {
        if (s == null) return "";
        String v = s.toLowerCase(Locale.ROOT);
        v = v.replace(':', ' ');
        v = v.replace('_', ' ');
        v = v.replace('-', ' ');
        v = v.replace('.', ' ');
        v = v.trim().replaceAll("\\s+", " ");
        return v;
    }
}
