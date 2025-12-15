
package meteordevelopment.meteorclient.systems.modules.world;

import baritone.api.pathing.goals.GoalBlock;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.farm.FarmManager;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.List;

public class AutoFarm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The range to search for crops.")
        .defaultValue(20)
        .min(1)
        .build());

    private final Setting<Boolean> replant = sgGeneral.add(new BoolSetting.Builder()
        .name("replant")
        .description("Replant crops after harvesting.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between harvests.")
        .defaultValue(5)
        .build());

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("crops")
        .description("Which crops to harvest.")
        .defaultValue(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS, Blocks.NETHER_WART)
        .filter(this::isCrop)
        .build());

    private final Setting<Boolean> useBonemeal = sgGeneral.add(new BoolSetting.Builder()
        .name("use-bonemeal")
        .description("Use Bonemeal on crops.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> redstoneControl = sgGeneral.add(new BoolSetting.Builder()
        .name("redstone-control")
        .description("Only function when receiving a redstone signal.")
        .defaultValue(false)
        .build());

    private boolean isCrop(Block block) {
        return block instanceof net.minecraft.block.CropBlock || block == Blocks.NETHER_WART;
    }

    public AutoFarm() {
        super(Categories.World, "auto-farm", "Automatically harvests and replants crops.");
    }

    private BlockPos currentTarget;
    private int timer;

    @Override
    public void onActivate() {
        timer = 0;
        currentTarget = null;
    }

    @Override
    public void onDeactivate() {
        if (BaritoneUtils.IS_AVAILABLE) {
            BaritoneUtils.cancelEverything(BaritoneUtils.getPrimaryBaritone());
        }
        currentTarget = null;
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (!BaritoneUtils.IS_AVAILABLE) {
            info("Baritone not found.");
            toggle();
            return;
        }

        if (redstoneControl.get() && !mc.world.isReceivingRedstonePower(mc.player.getBlockPos())) {
            return;
        }

        if (timer > 0) {
            timer--;
            return;
        }

        // 1. Check if we have a target
        if (currentTarget != null) {
            // Check if processed or invalid
            if (!FarmManager.get().isRipe(mc.world.getBlockState(currentTarget))) {
                // If not ripe, maybe we can bonemeal it?
                if (useBonemeal.get() && canBonemeal(currentTarget)) {
                    applyBonemeal(currentTarget);
                    timer = delay.get();
                    return;
                }
                
                // If really not targetable (e.g. valid after bonemeal, or finished)
                if (FarmManager.get().isRipe(mc.world.getBlockState(currentTarget))) {
                     // Proceed to harvest
                } else {
                    currentTarget = null; // Done or invalid
                    BaritoneUtils.cancelEverything(BaritoneUtils.getPrimaryBaritone());
                    return;
                }
            }

            // Check distance
            if (mc.player.getBlockPos().isWithinDistance(currentTarget, 4.5)) {
                harvest(currentTarget);
                timer = delay.get();
                return;
            }
        }

        // 2. Find new target if idle
        if (currentTarget == null) {
            List<BlockPos> crops = FarmManager.get().getRipeCrops(range.get(), blocks.get());
            
            // If none ripe, check for bonemeal targets
            if (crops.isEmpty() && useBonemeal.get()) {
                crops = findUnripeCrops();
            }

            if (crops.isEmpty()) return;

            // Sort by distance
            crops.sort(Comparator.comparingDouble(p -> mc.player.squaredDistanceTo(p.toCenterPos())));
            BlockPos best = crops.get(0);

            if (best != null) {
                currentTarget = best;
                pathTo(best);
            }
        }
    }
    
    private List<BlockPos> findUnripeCrops() {
        // Simple scan for unripe crops we can bonemeal
        // This is a simplified implementation for the example
        return new java.util.ArrayList<>(); 
    }

    private boolean canBonemeal(BlockPos pos) {
        return InvUtils.find(Items.BONE_MEAL).found();
    }
    
    private void applyBonemeal(BlockPos pos) {
         int slot = InvUtils.find(Items.BONE_MEAL).slot();
         if (slot != -1 && InvUtils.swap(slot, true)) {
             mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new net.minecraft.util.hit.BlockHitResult(pos.toCenterPos(), Direction.UP, pos, false));
             InvUtils.swapBack();
         }
    }

    private void pathTo(BlockPos pos) {
        Object baritone = BaritoneUtils.getPrimaryBaritone();
        Object customGoalProcess = BaritoneUtils.getCustomGoalProcess(baritone);
        BaritoneUtils.setGoalAndPath(customGoalProcess, new GoalBlock(pos));
    }

    private void harvest(BlockPos pos) {
        // Break
        mc.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
        // Instant break if possible (creative/high eff), otherwise loop handled by game, but we force packet here
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));
        
        // Replant
        if (replant.get()) {
            BlockState state = mc.world.getBlockState(pos);
            Item seed = FarmManager.get().getSeedFor(state.getBlock());
            
            // Smart Seed Selection
            if (seed == Items.AIR) {
                // Try to infer seed from items in inventory if block didn't give known seed
                // For now, simpler fallback: check common seeds
                if (InvUtils.find(Items.WHEAT_SEEDS).found()) seed = Items.WHEAT_SEEDS;
                else if (InvUtils.find(Items.POTATO).found()) seed = Items.POTATO;
                else if (InvUtils.find(Items.CARROT).found()) seed = Items.CARROT;
            }

            if (seed != Items.AIR) {
                int slot = InvUtils.findInHotbar(seed).slot();
                if (slot != -1) {
                    if (InvUtils.swap(slot, true)) {
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new net.minecraft.util.hit.BlockHitResult(pos.toCenterPos(), Direction.UP, pos, false));
                        InvUtils.swapBack();
                    }
                }
            }
        }
    }
}
