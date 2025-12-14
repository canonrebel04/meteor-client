
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

        if (timer > 0) {
            timer--;
            return;
        }

        // 1. Check if we have a target
        if (currentTarget != null) {
            // Check if processed or invalid
            if (!FarmManager.get().isRipe(mc.world.getBlockState(currentTarget))) {
                currentTarget = null; // Done or invalid
                BaritoneUtils.cancelEverything(BaritoneUtils.getPrimaryBaritone());
                return;
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
            if (seed != Items.AIR) {
                int slot = InvUtils.findInHotbar(seed).slot();
                if (slot != -1) {
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
}
