
package meteordevelopment.meteorclient.systems.storage;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class StorageManager extends System<StorageManager> {
    private static final StorageManager INSTANCE = new StorageManager();

    // Map BlockPos -> List of ItemData (item ID + count)
    private final Map<BlockPos, List<ItemData>> containers = new HashMap<>();
    
    private BlockPos lastInteractedPos = null;

    public StorageManager() {
        super("storage-manager");
    }

    public static StorageManager get() {
        return INSTANCE;
    }

    @Override
    public void init() {
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        // Track which block we just attempted to open
        lastInteractedPos = event.result.getBlockPos();
    }
    
    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler && lastInteractedPos != null) {
            // We are looking at a generic container (Chest, Barrel, Shulker)
            
            List<ItemData> items = new ArrayList<>();
            int rows = handler.getRows();
            int size = rows * 9;
            
            for (int i = 0; i < size; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (!stack.isEmpty()) {
                    Identifier id = Registries.ITEM.getId(stack.getItem());
                    if (id != null) {
                        items.add(new ItemData(id.toString(), stack.getCount()));
                    }
                }
            }
            
            containers.put(lastInteractedPos, items);
        } else {
            if (mc.currentScreen == null) {
                lastInteractedPos = null;
            }
        }
    }
    
    public BlockPos findItem(Item item) {
        if (item == null) return null;
        Identifier id = Registries.ITEM.getId(item);
        if (id == null) return null;
        
        String itemId = id.toString();
        for (Map.Entry<BlockPos, List<ItemData>> entry : containers.entrySet()) {
            for (ItemData data : entry.getValue()) {
                if (data.id.equals(itemId)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
    
    public Map<BlockPos, List<ItemData>> getAll() {
        return containers;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        NbtList list = new NbtList();
        
        for (Map.Entry<BlockPos, List<ItemData>> entry : containers.entrySet()) {
            NbtCompound containerTag = new NbtCompound();
            containerTag.putLong("pos", entry.getKey().asLong());
            
            NbtList itemsTag = new NbtList();
            for (ItemData data : entry.getValue()) {
                NbtCompound itemTag = new NbtCompound();
                itemTag.putString("id", data.id);
                itemTag.putInt("count", data.count);
                itemsTag.add(itemTag);
            }
            containerTag.put("items", itemsTag);
            list.add(containerTag);
        }
        
        tag.put("containers", list);
        return tag;
    }

    @Override
    public StorageManager fromTag(NbtCompound tag) {
        containers.clear();
        NbtList list = tag.getListOrEmpty("containers");
        for (NbtElement element : list) {
            if (!(element instanceof NbtCompound containerTag)) continue;
            
            long posLong = containerTag.getLong("pos", 0);
            if (posLong == 0) continue;
            BlockPos pos = BlockPos.fromLong(posLong);
            
            List<ItemData> items = new ArrayList<>();
            NbtList itemsTag = containerTag.getListOrEmpty("items");
            for (NbtElement itemElement : itemsTag) {
                if (!(itemElement instanceof NbtCompound itemTag)) continue;
                String id = itemTag.getString("id", "");
                int count = itemTag.getInt("count", 0);
                if (!id.isEmpty() && count > 0) {
                    items.add(new ItemData(id, count));
                }
            }
            containers.put(pos, items);
        }
        return this;
    }
    
    public record ItemData(String id, int count) {}
}
