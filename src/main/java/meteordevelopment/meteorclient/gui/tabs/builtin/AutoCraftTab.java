/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.tabs.builtin;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.settings.ItemSettingScreen;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WItemWithLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.systems.autocraft.AutoCraftContext;
import meteordevelopment.meteorclient.systems.autocraft.AutoCraftMemory;
import meteordevelopment.meteorclient.systems.autocraft.AutoCraftPlanner;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.AutoCraft;
import meteordevelopment.meteorclient.systems.modules.world.AutoSmelt;
import meteordevelopment.meteorclient.systems.modules.world.StorageScanner;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import static meteordevelopment.meteorclient.MeteorClient.mc;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.LinkedHashSet;

public class AutoCraftTab extends Tab {
    public AutoCraftTab() {
        super("AutoCraft");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new AutoCraftScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof AutoCraftScreen;
    }

    private static class AutoCraftScreen extends WindowTabScreen {
        private WTextBox amount;
        private WLabel status;
        private WTable missingTable;
        private WTable craftsTable;
        private WTable containersTable;
        private WCheckbox useOnlySelected;

        private ItemSetting targetItem;
        private WItemWithLabel targetItemWidget;

        public AutoCraftScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            add(theme.label("Scans nearby storage by opening it, remembers contents, and shows missing items for a crafting output."))
                .expandX();

            add(theme.horizontalSeparator("Target")).expandX();

            targetItem = new ItemSetting.Builder()
                .name("target-item")
                .description("Target item to craft")
                .defaultValue(Items.IRON_PICKAXE)
                .onChanged(item -> refresh())
                .build();

            WTable targetRow = add(theme.table()).expandX().widget();
            targetRow.add(theme.label("Item"));
            targetItemWidget = targetRow.add(theme.itemWithLabel(targetItem.get().getDefaultStack(), Names.get(targetItem.get()))).expandX().widget();
            targetRow.add(theme.button("Pick")).widget().action = () -> mc.setScreen(new ItemSettingScreen(theme, targetItem));
            targetRow.row();

            targetRow.add(theme.label("Amount"));
            amount = targetRow.add(theme.textBox("1")).minWidth(80).widget();
            targetRow.row();

            status = add(theme.label("")).expandX().widget();

            add(theme.horizontalSeparator("Actions")).expandX();

            WTable actions = add(theme.table()).expandX().widget();

            actions.add(theme.button("Scan Storage")).expandX().widget().action = () -> {
                var mod = Modules.get().get(StorageScanner.class);
                if (mod == null) return;
                mod.toggle();
                mod.sendToggledMsg();
                refresh();
            };

            actions.add(theme.button("Mine Missing")).expandX().widget().action = () -> {
                var plan = computePlan();
                var lines = plan.missingLeaves();
                if (lines.isEmpty()) return;

                LinkedHashSet<Block> blocks = new LinkedHashSet<>();
                for (var line : lines) {
                    if (line.missing() <= 0) continue;
                    addMineTargetsFor(line.item(), blocks);
                }

                if (blocks.isEmpty()) {
                    ChatUtils.infoPrefix("AutoCraft", "No mineable blocks mapped for missing items.");
                    return;
                }

                PathManagers.get().mine(blocks.toArray(new Block[0]));
            };

            actions.add(theme.button("AutoSmelt")).expandX().widget().action = () -> {
                var mod = Modules.get().get(AutoSmelt.class);
                if (mod == null) return;
                mod.toggle();
                mod.sendToggledMsg();
            };

            actions.add(theme.button("AutoCraft")).expandX().widget().action = () -> {
                int amt = parseIntSafe(amount.get(), 1);
                Item target = targetItem.get();

                var mod = Modules.get().get(AutoCraft.class);
                if (mod == null) return;

                mod.setTarget(target, amt);
                if (!mod.isActive()) {
                    mod.toggle();
                    mod.sendToggledMsg();
                }
            };

            actions.add(theme.button("Scan + AutoCraft")).expandX().widget().action = () -> {
                int amt = parseIntSafe(amount.get(), 1);
                Item target = targetItem.get();

                var mem = AutoCraftMemory.get();
                int chestCount = mem != null ? mem.chestCount() : 0;

                // If we already have memory, just craft.
                if (chestCount > 0) {
                    var mod = Modules.get().get(AutoCraft.class);
                    if (mod == null) return;
                    mod.setTarget(target, amt);
                    if (!mod.isActive()) {
                        mod.toggle();
                        mod.sendToggledMsg();
                    }
                    return;
                }

                // Otherwise run a scan then craft.
                AutoCraftContext.setPendingAutoCraft(target, amt);
                var scan = Modules.get().get(StorageScanner.class);
                if (scan == null) return;
                if (!scan.isActive()) {
                    scan.toggle();
                    scan.sendToggledMsg();
                }
            };

            actions.row();

            add(theme.horizontalSeparator("Containers")).expandX();

            var mem0 = AutoCraftMemory.get();
            boolean initialUseSelectedOnly = mem0 != null && mem0.useOnlySelectedContainers();

            WTable selectionRow = add(theme.table()).expandX().widget();
            useOnlySelected = selectionRow.add(theme.checkbox(initialUseSelectedOnly)).widget();
            selectionRow.add(theme.label("Use only selected containers")).expandCellX();
            useOnlySelected.action = () -> {
                var mem = AutoCraftMemory.get();
                if (mem == null) return;
                mem.setUseOnlySelectedContainers(useOnlySelected.checked);
                refresh();
            };
            selectionRow.row();

            WTable selectionButtons = add(theme.table()).expandX().widget();
            selectionButtons.add(theme.button("Select All")).widget().action = () -> {
                var mem = AutoCraftMemory.get();
                if (mem == null) return;
                mem.setAllSnapshotsEnabled(true);
                refresh();
            };
            selectionButtons.add(theme.button("Select None")).widget().action = () -> {
                var mem = AutoCraftMemory.get();
                if (mem == null) return;
                mem.setAllSnapshotsEnabled(false);
                refresh();
            };
            selectionButtons.add(theme.button("Clear Chests")).widget().action = () -> {
                var mem = AutoCraftMemory.get();
                if (mem == null) return;
                mem.clearChests();
                refresh();
            };
            selectionButtons.add(theme.button("Reset Selection")).widget().action = () -> {
                var mem = AutoCraftMemory.get();
                if (mem == null) return;
                mem.setAllSnapshotsEnabled(true);
                mem.setUseOnlySelectedContainers(false);
                refresh();
            };
            selectionButtons.add(theme.button("Clear Memory")).widget().action = () -> {
                var mem = AutoCraftMemory.get();
                if (mem == null) return;
                mem.clear();
                refresh();
            };
            selectionButtons.row();

            containersTable = add(theme.table()).expandX().widget();

            add(theme.horizontalSeparator("Missing (Base Items)"))
                .expandX();
            missingTable = add(theme.table()).expandX().widget();

            add(theme.horizontalSeparator("Crafts (Intermediates)"))
                .expandX();
            craftsTable = add(theme.table()).expandX().widget();

            amount.action = this::refresh;

            refresh();
        }

        private void refresh() {
            var mem = AutoCraftMemory.get();
            int chestCount = mem != null ? mem.chestCount() : 0;
            int enabledCount = mem != null ? mem.enabledChestCount() : 0;
            boolean useSelectedOnly = mem != null && mem.useOnlySelectedContainers();

            int amt = parseIntSafe(amount.get(), 1);

            Item target = targetItem.get();
            if (targetItemWidget != null) targetItemWidget.set(target.getDefaultStack());
            String targetText = target.getName().getString();
            status.set(
                "Memory: " + chestCount + " containers (" + enabledCount + " enabled)" +
                    "  •  Containers: " + (useSelectedOnly ? "selected" : "all") +
                    "  •  Target: " + targetText + " x" + amt
            );

            if (useOnlySelected != null) useOnlySelected.checked = useSelectedOnly;

            rebuildContainersTable();

            rebuildMissingTable();
            rebuildCraftsTable();
        }

        private void rebuildContainersTable() {
            if (containersTable == null) return;

            containersTable.clear();
            containersTable.add(theme.label("Use"));
            containersTable.add(theme.label("Dimension"));
            containersTable.add(theme.label("Pos"));
            containersTable.row();

            var mem = AutoCraftMemory.get();
            if (mem == null || mem.snapshots().isEmpty()) {
                containersTable.add(theme.label("No remembered containers. Run 'Scan Storage' first."))
                    .expandCellX();
                containersTable.row();
                return;
            }

            for (var s : mem.snapshots()) {
                if (s == null) continue;

                WCheckbox cb = containersTable.add(theme.checkbox(s.isEnabled())).widget();
                cb.action = () -> {
                    mem.setSnapshotEnabled(s, cb.checked);
                    refresh();
                };

                containersTable.add(theme.label(s.getDimensionId()));
                containersTable.add(theme.label(s.getX() + ", " + s.getY() + ", " + s.getZ()));
                containersTable.row();
            }
        }

        private void rebuildMissingTable() {
            missingTable.clear();

            missingTable.add(theme.label("Item"));
            missingTable.add(theme.label("Need"));
            missingTable.add(theme.label("Inv"));
            missingTable.add(theme.label("Chests"));
            missingTable.add(theme.label("Missing"));
            missingTable.row();

            var lines = computePlan().missingLeaves();
            if (lines.isEmpty()) {
                missingTable.add(theme.label("No data (invalid target, unknown recipe, or nothing needed)."))
                    .expandCellX();
                missingTable.row();
                return;
            }

            for (var line : lines) {
                String name = line.item() != null ? line.item().getName().getString() : "?";
                missingTable.add(theme.label(name));
                missingTable.add(theme.label(String.valueOf(line.needed())));
                missingTable.add(theme.label(String.valueOf(line.inInventory())));
                missingTable.add(theme.label(String.valueOf(line.inChests())));
                missingTable.add(theme.label(String.valueOf(line.missing())));
                missingTable.row();
            }
        }

        private void rebuildCraftsTable() {
            craftsTable.clear();

            craftsTable.add(theme.label("Item"));
            craftsTable.add(theme.label("Need"));
            craftsTable.row();

            Item target = targetItem.get();
            var crafts = computePlan().intermediateCrafts();
            if (crafts.isEmpty()) {
                craftsTable.add(theme.label("No intermediate crafting steps."))
                    .expandCellX();
                craftsTable.row();
                return;
            }

            boolean addedAny = false;

            for (var line : crafts) {
                if (target != null && line.item() == target) continue;
                String name = line.item() != null ? line.item().getName().getString() : "?";
                craftsTable.add(theme.label(name));
                craftsTable.add(theme.label(String.valueOf(line.needed())));
                craftsTable.row();
                addedAny = true;
            }

            if (!addedAny) {
                craftsTable.clear();
                craftsTable.add(theme.label("Item"));
                craftsTable.add(theme.label("Need"));
                craftsTable.row();
                craftsTable.add(theme.label("No intermediate crafting steps."))
                    .expandCellX();
                craftsTable.row();
            }
        }

        private AutoCraftPlanner.Plan computePlan() {
            int amt = parseIntSafe(amount.get(), 1);
            return AutoCraftPlanner.computePlan(targetItem.get(), amt);
        }

        private static int parseIntSafe(String text, int def) {
            try {
                int v = Integer.parseInt(text.trim());
                return v > 0 ? v : def;
            } catch (Throwable ignored) {
                return def;
            }
        }

        private static void addMineTargetsFor(Item item, LinkedHashSet<Block> blocks) {
            if (item == null) return;

            // Minimal mapping for common craft chains.
            if (item == Items.IRON_INGOT || item == Items.RAW_IRON) {
                blocks.add(Blocks.IRON_ORE);
                blocks.add(Blocks.DEEPSLATE_IRON_ORE);
            } else if (item == Items.GOLD_INGOT || item == Items.RAW_GOLD) {
                blocks.add(Blocks.GOLD_ORE);
                blocks.add(Blocks.DEEPSLATE_GOLD_ORE);
                blocks.add(Blocks.NETHER_GOLD_ORE);
            } else if (item == Items.COPPER_INGOT || item == Items.RAW_COPPER) {
                blocks.add(Blocks.COPPER_ORE);
                blocks.add(Blocks.DEEPSLATE_COPPER_ORE);
            } else if (item == Items.DIAMOND) {
                blocks.add(Blocks.DIAMOND_ORE);
                blocks.add(Blocks.DEEPSLATE_DIAMOND_ORE);
            } else if (item == Items.COAL) {
                blocks.add(Blocks.COAL_ORE);
                blocks.add(Blocks.DEEPSLATE_COAL_ORE);
            } else if (item == Items.REDSTONE) {
                blocks.add(Blocks.REDSTONE_ORE);
                blocks.add(Blocks.DEEPSLATE_REDSTONE_ORE);
            } else if (item == Items.LAPIS_LAZULI) {
                blocks.add(Blocks.LAPIS_ORE);
                blocks.add(Blocks.DEEPSLATE_LAPIS_ORE);
            } else if (item == Items.EMERALD) {
                blocks.add(Blocks.EMERALD_ORE);
                blocks.add(Blocks.DEEPSLATE_EMERALD_ORE);
            } else if (item == Items.QUARTZ) {
                blocks.add(Blocks.NETHER_QUARTZ_ORE);
            } else if (item == Items.CHARCOAL) {
                // Charcoal comes from smelting logs. Mine logs as the leaf resource.
                addLogTargets(blocks);
            } else if (item == Items.OAK_LOG || item == Items.SPRUCE_LOG || item == Items.BIRCH_LOG
                || item == Items.JUNGLE_LOG || item == Items.ACACIA_LOG || item == Items.DARK_OAK_LOG
                || item == Items.MANGROVE_LOG || item == Items.CHERRY_LOG
                || item == Items.CRIMSON_STEM || item == Items.WARPED_STEM) {
                // If planner ends up with a specific log type as leaf.
                addLogTargets(blocks);
            } else if (item == Items.SAND) {
                blocks.add(Blocks.SAND);
                blocks.add(Blocks.RED_SAND);
            } else if (item == Items.GRAVEL) {
                blocks.add(Blocks.GRAVEL);
            } else if (item == Items.FLINT) {
                blocks.add(Blocks.GRAVEL);
            } else if (item == Items.CLAY_BALL) {
                blocks.add(Blocks.CLAY);
            } else if (item == Items.COBBLESTONE) {
                blocks.add(Blocks.COBBLESTONE);
            } else if (item == Items.STONE) {
                blocks.add(Blocks.STONE);
            } else if (item == Items.DEEPSLATE) {
                blocks.add(Blocks.DEEPSLATE);
            } else if (item == Items.COBBLED_DEEPSLATE) {
                blocks.add(Blocks.COBBLED_DEEPSLATE);
            } else if (item == Items.DIRT) {
                blocks.add(Blocks.DIRT);
            } else if (item == Items.NETHERRACK) {
                blocks.add(Blocks.NETHERRACK);
            } else if (item == Items.END_STONE) {
                blocks.add(Blocks.END_STONE);
            } else if (item == Items.ICE) {
                blocks.add(Blocks.ICE);
            } else if (item == Items.PACKED_ICE) {
                blocks.add(Blocks.PACKED_ICE);
            } else if (item == Items.BLUE_ICE) {
                blocks.add(Blocks.BLUE_ICE);
            } else if (item == Items.AMETHYST_SHARD) {
                // Any bud/cluster produces shards when broken (without silk touch).
                blocks.add(Blocks.AMETHYST_CLUSTER);
                blocks.add(Blocks.LARGE_AMETHYST_BUD);
                blocks.add(Blocks.MEDIUM_AMETHYST_BUD);
                blocks.add(Blocks.SMALL_AMETHYST_BUD);
            } else if (item == Items.STICK
                || item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS || item == Items.BIRCH_PLANKS
                || item == Items.JUNGLE_PLANKS || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS
                || item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS
                || item == Items.CRIMSON_PLANKS || item == Items.WARPED_PLANKS
                || item == Items.BAMBOO_PLANKS) {
                // Common leaf items in recipes; source is logs/stems.
                addLogTargets(blocks);
            } else if (item == Items.GLASS || item == Items.GLASS_PANE) {
                // Glass is smelted from sand.
                blocks.add(Blocks.SAND);
                blocks.add(Blocks.RED_SAND);
            } else if (item == Items.PAPER) {
                // Paper comes from sugar cane.
                blocks.add(Blocks.SUGAR_CANE);
            } else if (item == Items.BAMBOO) {
                blocks.add(Blocks.BAMBOO);
            } else if (item == Items.CACTUS) {
                blocks.add(Blocks.CACTUS);
            } else if (item == Items.KELP) {
                blocks.add(Blocks.KELP);
                blocks.add(Blocks.KELP_PLANT);
            } else if (item == Items.SEA_PICKLE) {
                blocks.add(Blocks.SEA_PICKLE);
            } else if (item == Items.GLOWSTONE_DUST) {
                blocks.add(Blocks.GLOWSTONE);
            } else if (item == Items.NETHER_WART) {
                blocks.add(Blocks.NETHER_WART);
            }
        }

        private static void addLogTargets(LinkedHashSet<Block> blocks) {
            // Overworld
            blocks.add(Blocks.OAK_LOG);
            blocks.add(Blocks.SPRUCE_LOG);
            blocks.add(Blocks.BIRCH_LOG);
            blocks.add(Blocks.JUNGLE_LOG);
            blocks.add(Blocks.ACACIA_LOG);
            blocks.add(Blocks.DARK_OAK_LOG);
            blocks.add(Blocks.MANGROVE_LOG);
            blocks.add(Blocks.CHERRY_LOG);

            // Nether
            blocks.add(Blocks.CRIMSON_STEM);
            blocks.add(Blocks.WARPED_STEM);
        }
    }
}
