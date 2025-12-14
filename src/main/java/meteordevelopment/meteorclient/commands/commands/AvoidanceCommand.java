
package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.world.Avoidance;
import meteordevelopment.meteorclient.systems.modules.world.AvoidanceManager;
import net.minecraft.command.CommandSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public class AvoidanceCommand extends Command {
    public AvoidanceCommand() {
        super("avoidance", "Manage avoidance zones.", "av");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("add")
            .then(argument("radius", IntegerArgumentType.integer(1))
                .then(argument("multiplier", DoubleArgumentType.doubleArg(0))
                    .then(argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            int radius = IntegerArgumentType.getInteger(ctx, "radius");
                            double multiplier = DoubleArgumentType.getDouble(ctx, "multiplier");
                            String name = StringArgumentType.getString(ctx, "name");
                            
                            BlockPos center = mc.player.getBlockPos();
                            Box box = new Box(
                                center.getX() - radius, center.getY() - radius, center.getZ() - radius,
                                center.getX() + radius, center.getY() + radius, center.getZ() + radius
                            );

                            Avoidance avoid = new Avoidance(box, multiplier, name);
                            AvoidanceManager.get().add(avoid);
                            info("Added avoidance zone '" + name + "' at current location.");
                            return SINGLE_SUCCESS;
                        })))));

        builder.then(literal("remove")
            .then(argument("name", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    boolean removed = false;
                    // Copy list to avoid concurrent mod
                    for (Avoidance a : new java.util.ArrayList<>(AvoidanceManager.get().getList())) {
                        if (a.name.equalsIgnoreCase(name)) {
                            AvoidanceManager.get().remove(a);
                            removed = true;
                            // Only remove one match
                            break;
                        }
                    }
                    if (removed) info("Removed avoidance zone '" + name + "'.");
                    else error("No zone found with name '" + name + "'.");
                    return SINGLE_SUCCESS;
                })));

        builder.then(literal("list").executes(ctx -> {
            info("Active Avoidance Zones:");
            for (Avoidance a : AvoidanceManager.get()) {
                info(String.format("- %s (%.1fx)", a.name, a.multiplier));
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(ctx -> {
            AvoidanceManager.get().getList().clear();
            AvoidanceManager.get().save();
            info("Cleared all avoidance zones.");
            return SINGLE_SUCCESS;
        }));
    }
}
