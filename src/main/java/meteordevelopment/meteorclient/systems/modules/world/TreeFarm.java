
package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TreeFarm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Block>> trees = sgGeneral.add(new BlockListSetting.Builder()
        .name("logs")
        .description("Which log types to mine.")
        .defaultValue(Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG, Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG)
        .filter(this::isLog)
        .build());

    private final Setting<Boolean> replant = sgGeneral.add(new BoolSetting.Builder()
        .name("replant")
        .description("Replant saplings after chopping.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> pickupDelay = sgGeneral.add(new IntSetting.Builder()
        .name("pickup-delay")
        .description("Ticks to wait for log pickup before moving to next tree.")
        .defaultValue(40)
        .min(0)
        .max(100)
        .build());

    private boolean isLog(Block block) {
        return block.getDefaultState().isIn(net.minecraft.registry.tag.BlockTags.LOGS);
    }

    public TreeFarm() {
        super(Categories.World, "tree-farm", "Uses Baritone to mine trees with replanting.");
    }

    private boolean baritoneActive = false;
    private BlockPos lastMinedTreeBase = null;
    private int pickupTimer = 0;
    private Map<Block, Item> logToSapling = new HashMap<>();

    @Override
    public void onActivate() {
        initSaplingMap();
        lastMinedTreeBase = null;
        pickupTimer = 0;
        startBaritone();
    }

    @Override
    public void onDeactivate() {
        if (pausedForPickup) {
            ChatUtils.sendPlayerMsg("#resume");
        }
        stopBaritone();
        baritoneActive = false;
        pausedForPickup = false;
    }

    private void initSaplingMap() {
        logToSapling.put(Blocks.OAK_LOG, Items.OAK_SAPLING);
        logToSapling.put(Blocks.BIRCH_LOG, Items.BIRCH_SAPLING);
        logToSapling.put(Blocks.SPRUCE_LOG, Items.SPRUCE_SAPLING);
        logToSapling.put(Blocks.JUNGLE_LOG, Items.JUNGLE_SAPLING);
        logToSapling.put(Blocks.ACACIA_LOG, Items.ACACIA_SAPLING);
        logToSapling.put(Blocks.DARK_OAK_LOG, Items.DARK_OAK_SAPLING);
    }

    private void startBaritone() {
        if (!BaritoneUtils.IS_AVAILABLE) {
            error("Baritone not available.");
            toggle();
            return;
        }

        StringBuilder cmd = new StringBuilder("#mine");
        for (Block log : trees.get()) {
            String id = net.minecraft.registry.Registries.BLOCK.getId(log).toString();
            cmd.append(" ").append(id);
        }

        ChatUtils.sendPlayerMsg(cmd.toString());
        baritoneActive = true;
        info("Started Baritone tree mining.");
    }

    private void stopBaritone() {
        if (BaritoneUtils.IS_AVAILABLE) {
            ChatUtils.sendPlayerMsg("#stop");
        }
    }

    private boolean pausedForPickup = false;

    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (!baritoneActive) return;

        // Check if we're near a tree base that was just mined
        if (lastMinedTreeBase == null) {
            lastMinedTreeBase = findNearbyTreeBase();
            if (lastMinedTreeBase != null) {
                // Found a new tree base, pause Baritone to collect drops
                ChatUtils.sendPlayerMsg("#pause");
                pausedForPickup = true;
            }
        }

        // If we have a tree base and there are log items nearby
        if (lastMinedTreeBase != null) {
            if (pickupTimer > 0) {
                pickupTimer--;
                
                // Check if logs are still dropping
                if (hasNearbyLogItems(lastMinedTreeBase)) {
                    pickupTimer = pickupDelay.get(); // Reset timer
                }
                
                return; // Wait
            }

            // Pickup period finished, replant and resume
            if (replant.get()) {
                replantAt(lastMinedTreeBase);
            }
            
            if (pausedForPickup) {
                ChatUtils.sendPlayerMsg("#resume");
                pausedForPickup = false;
            }
            
            lastMinedTreeBase = null;
        }
    }

    private BlockPos findNearbyTreeBase() {
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        
        // Check ground blocks near player for potential tree bases
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = -1; y <= 1; y++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    Block below = mc.world.getBlockState(pos).getBlock();
                    
                    // Tree base indicators: dirt/grass with air above, and log items nearby
                    if ((below == Blocks.DIRT || below == Blocks.GRASS_BLOCK || below == Blocks.PODZOL) &&
                        mc.world.getBlockState(pos.up()).isAir() &&
                        hasNearbyLogItems(pos)) {
                        
                        pickupTimer = pickupDelay.get();
                        return pos;
                    }
                }
            }
        }
        
        return null;
    }

    private boolean hasNearbyLogItems(BlockPos center) {
        if (mc.world == null) return false;
        
        Box box = new Box(center).expand(4);
        List<ItemEntity> items = mc.world.getEntitiesByClass(ItemEntity.class, box, 
            entity -> entity.getStack().getItem().getDefaultStack().isIn(net.minecraft.registry.tag.ItemTags.LOGS));
        
        return !items.isEmpty();
    }

    private void replantAt(BlockPos base) {
        if (mc.player == null || mc.world == null) return;
        
        // Try to determine wood type from nearby logs
        Item sapling = null;
        Box box = new Box(base).expand(5);
        List<ItemEntity> logItems = mc.world.getEntitiesByClass(ItemEntity.class, box,
            entity -> entity.getStack().getItem().getDefaultStack().isIn(net.minecraft.registry.tag.ItemTags.LOGS));
        
        if (!logItems.isEmpty()) {
            Item logItem = logItems.get(0).getStack().getItem();
            // Convert log item to block
            Block logBlock = Block.getBlockFromItem(logItem);
            sapling = logToSapling.get(logBlock);
        }
        
        // Fallback: use any sapling
        if (sapling == null) {
            for (Item s : logToSapling.values()) {
                if (InvUtils.findInHotbar(s).found()) {
                    sapling = s;
                    break;
                }
            }
        }
        
        if (sapling != null) {
            int slot = InvUtils.findInHotbar(sapling).slot();
            if (slot != -1 && mc.player.squaredDistanceTo(base.toCenterPos()) < 25) {
                if (InvUtils.swap(slot, true)) {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new net.minecraft.util.hit.BlockHitResult(base.up().toCenterPos(), net.minecraft.util.math.Direction.UP, base, false));
                    InvUtils.swapBack();
                    info("Replanted sapling at %s", base.toShortString());
                }
            }
        }
    }
}
