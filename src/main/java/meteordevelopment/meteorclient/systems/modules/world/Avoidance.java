
package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.utils.misc.ISerializable;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Box;

public class Avoidance implements ISerializable<Avoidance> {
    public Box box;
    public double multiplier;
    public String name;

    public Avoidance(Box box, double multiplier, String name) {
        this.box = box;
        this.multiplier = multiplier;
        this.name = name;
    }

    public Avoidance() {}

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putString("name", name);
        tag.putDouble("multiplier", multiplier);
        tag.putDouble("minX", box.minX);
        tag.putDouble("minY", box.minY);
        tag.putDouble("minZ", box.minZ);
        tag.putDouble("maxX", box.maxX);
        tag.putDouble("maxY", box.maxY);
        tag.putDouble("maxZ", box.maxZ);
        return tag;
    }

    @Override
    public Avoidance fromTag(NbtCompound tag) {
        if (tag.contains("name")) name = tag.getString("name").orElse("");
        if (tag.contains("multiplier")) multiplier = tag.getDouble("multiplier").orElse(1.0);
        
        double minX = tag.getDouble("minX").orElse(0.0);
        double minY = tag.getDouble("minY").orElse(0.0);
        double minZ = tag.getDouble("minZ").orElse(0.0);
        double maxX = tag.getDouble("maxX").orElse(0.0);
        double maxY = tag.getDouble("maxY").orElse(0.0);
        double maxZ = tag.getDouble("maxZ").orElse(0.0);

        box = new Box(minX, minY, minZ, maxX, maxY, maxZ);
        return this;
    }
}
