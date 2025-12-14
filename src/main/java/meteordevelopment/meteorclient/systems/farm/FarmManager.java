
package meteordevelopment.meteorclient.systems.farm;

import meteordevelopment.meteorclient.systems.System;
import net.minecraft.block.*;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class FarmManager extends System<FarmManager> {
    private static final FarmManager INSTANCE = new FarmManager();

    public static FarmManager get() {
        return INSTANCE;
    }

    public FarmManager() {
        super("Farm Manager");
    }

    @Override
    public void init() {
        // No specific init needed yet
    }

    public List<BlockPos> getRipeCrops(double range, List<Block> filter) {
        List<BlockPos> ripeCrops = new ArrayList<>();
        if (mc.player == null || mc.world == null) return ripeCrops;

        int r = (int) Math.ceil(range);
        BlockPos playerPos = mc.player.getBlockPos();

        for (int x = -r; x <= r; x++) {
            for (int y = -1; y <= 2; y++) { // Check slightly above/below
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (mc.player.squaredDistanceTo(pos.toCenterPos()) > range * range) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    if (!filter.isEmpty() && !filter.contains(state.getBlock())) continue;

                    if (isRipe(state)) {
                        ripeCrops.add(pos);
                    }
                }
            }
        }
        return ripeCrops;
    }

    public boolean isRipe(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        if (block == Blocks.NETHER_WART) {
            return state.get(NetherWartBlock.AGE) == 3;
        }
        return false;
    }

    public Item getSeedFor(Block block) {
        if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (block == Blocks.POTATOES) return Items.POTATO;
        if (block == Blocks.CARROTS) return Items.CARROT;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        if (block == Blocks.NETHER_WART) return Items.NETHER_WART;
        return Items.AIR;
    }

    public List<BlockPos> getTreeBases(double range, List<Block> filter) {
        List<BlockPos> trees = new ArrayList<>();
        if (mc.player == null || mc.world == null) return trees;

        int r = (int) Math.ceil(range);
        BlockPos playerPos = mc.player.getBlockPos();

        for (int x = -r; x <= r; x++) {
            for (int y = -5; y <= 10; y++) { // Trees can be tall
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (mc.player.squaredDistanceTo(pos.toCenterPos()) > range * range) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    if (!filter.isEmpty() && !filter.contains(state.getBlock())) continue;

                    if (isTreeBase(pos)) {
                        trees.add(pos);
                    }
                }
            }
        }
        return trees;
    }

    public boolean isTreeBase(BlockPos pos) {
        if (mc.world == null) return false;
        
        BlockState state = mc.world.getBlockState(pos);
        Block block = state.getBlock();
        
        // Must be a log
        if (!(block instanceof net.minecraft.block.PillarBlock) || 
            !(block.getDefaultState().isIn(net.minecraft.registry.tag.BlockTags.LOGS))) {
            return false;
        }
        
        // Must have dirt/grass/podzol below
        BlockState below = mc.world.getBlockState(pos.down());
        if (!(below.getBlock() == Blocks.DIRT || 
              below.getBlock() == Blocks.GRASS_BLOCK || 
              below.getBlock() == Blocks.PODZOL ||
              below.getBlock() == Blocks.COARSE_DIRT)) {
            return false;
        }
        
        // Must have leaves or logs above (within 10 blocks)
        for (int i = 1; i <= 10; i++) {
            BlockState above = mc.world.getBlockState(pos.up(i));
            if (above.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) ||
                above.isIn(net.minecraft.registry.tag.BlockTags.LOGS)) {
                return true;
            }
            if (above.isAir()) continue;
            break; // Hit something else
        }
        
        return false;
    }
}
