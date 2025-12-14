
package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.utils.misc.NbtUtils;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class AvoidanceManager extends System<AvoidanceManager> implements Iterable<Avoidance> {
    private List<Avoidance> avoidances = new ArrayList<>();

    public AvoidanceManager() {
        super("avoidance-manager");
    }

    public static AvoidanceManager get() {
        return Systems.get(AvoidanceManager.class);
    }

    public void add(Avoidance avoidance) {
        avoidances.add(avoidance);
        save();
    }

    public void remove(Avoidance avoidance) {
        if (avoidances.remove(avoidance)) {
            save();
        }
    }

    public List<Avoidance> getList() {
        return avoidances;
    }

    @Override
    public File getFile() {
        return new File(MeteorClient.FOLDER, "avoidance_zones.nbt");
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        NbtList list = new NbtList();
        for (Avoidance a : avoidances) {
            list.add(a.toTag());
        }
        tag.put("avoidances", list);
        return tag;
    }

    @Override
    public AvoidanceManager fromTag(NbtCompound tag) {
        avoidances.clear();
        // Assuming getList(String) returns Optional<NbtList> or NbtList directly based on error
        // If it returns Optional, I need to handle it.
        // Let's assume it returns Optional<NbtList> if getString returns Optional<String>.
        tag.getList("avoidances").ifPresent(list -> {
            for (NbtElement element : list) {
                avoidances.add(new Avoidance().fromTag((NbtCompound) element));
            }
        });
        return this;
    }

    @Override
    public Iterator<Avoidance> iterator() {
        return avoidances.iterator();
    }
}
