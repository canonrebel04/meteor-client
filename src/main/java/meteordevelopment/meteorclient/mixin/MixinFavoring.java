
package meteordevelopment.meteorclient.mixin;

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import meteordevelopment.meteorclient.systems.modules.world.Avoidance;
import meteordevelopment.meteorclient.systems.modules.world.AvoidanceManager;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Favoring.class)
public class MixinFavoring {
    @Shadow
    private Long2DoubleOpenHashMap favorings;

    @Inject(method = "<init>(Lbaritone/api/utils/IPlayerContext;Lbaritone/api/pathing/calc/IPath;Lbaritone/pathing/movement/CalculationContext;)V", at = @At("TAIL"))
    private void onInit(IPlayerContext ctx, IPath previous, CalculationContext context, CallbackInfo ci) {
        if (AvoidanceManager.get() == null) return;
        
        for (Avoidance avoidance : AvoidanceManager.get()) {
            Box box = avoidance.box;
            double multiplier = avoidance.multiplier;
            
            int minX = (int) Math.floor(box.minX);
            int minY = (int) Math.floor(box.minY);
            int minZ = (int) Math.floor(box.minZ);
            int maxX = (int) Math.ceil(box.maxX);
            int maxY = (int) Math.ceil(box.maxY);
            int maxZ = (int) Math.ceil(box.maxZ);

            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        long hash = longHash(x, y, z);
                        double current = favorings.get(hash);
                        favorings.put(hash, current * multiplier);
                    }
                }
            }
        }
    }

    private static long longHash(int x, int y, int z) {
        long hash = 3241;
        hash = 3457689L * hash + x;
        hash = 8734625L * hash + y;
        hash = 2873465L * hash + z;
        return hash;
    }
}
