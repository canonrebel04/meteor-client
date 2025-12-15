/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.arguments.ModuleArgumentType;
import meteordevelopment.meteorclient.systems.storage.StorageManager;
import meteordevelopment.meteorclient.systems.waypoints.Route;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class RouteCommand extends Command {
    public RouteCommand() {
        super("route", "Manages pathfinding routes.", "routes");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // LIST
        builder.then(literal("list").executes(ctx -> {
            if (Waypoints.get().routes.isEmpty()) {
                info("No routes created.");
            } else {
                info(Formatting.WHITE + "Routes:");
                for (Route route : Waypoints.get().routes) {
                    info("- (highlight)%s(default): %d waypoints, Distance: %.1fm", route.name, route.waypoints.size(), route.getTotalDistance());
                }
            }
            return SINGLE_SUCCESS;
        }));

        // CREATE
        builder.then(literal("create")
            .then(argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                Route route = new Route();
                route.name = name;
                Waypoints.get().routes.add(route);
                Waypoints.get().save();
                info("Created route: (highlight)%s(default)", name);
                return SINGLE_SUCCESS;
            }))
        );
        
        // DELETE
        builder.then(literal("delete")
             .then(argument("name", StringArgumentType.string()).executes(ctx -> {
                 String name = StringArgumentType.getString(ctx, "name");
                 Route toRemove = null;
                 for (Route r : Waypoints.get().routes) {
                     if (r.name.equalsIgnoreCase(name)) {
                         toRemove = r;
                         break;
                     }
                 }
                 
                 if (toRemove != null) {
                     Waypoints.get().routes.remove(toRemove);
                     Waypoints.get().save();
                     info("Deleted route: (highlight)%s(default)", name);
                 } else {
                     error("Route not found.");
                 }
                 return SINGLE_SUCCESS;
             }))
        );

        // GENERATE
        builder.then(literal("generate")
            .then(argument("name", StringArgumentType.string())
                .then(argument("item", StringArgumentType.greedyString()).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    String itemId = StringArgumentType.getString(ctx, "item");
                    
                    Item item = Registries.ITEM.get(Identifier.of(itemId));
                    if (item == null) {
                        error("Invalid item.");
                        return SINGLE_SUCCESS;
                    }
                    
                    List<BlockPos> locations = StorageManager.get().findAll(item);
                    if (locations.isEmpty()) {
                        error("No containers found with item: " + itemId);
                        return SINGLE_SUCCESS;
                    }
                    
                    // Create Route
                    Route route = new Route();
                    route.name = name;
                    
                    // Create temp waypoints for these locations
                    for (BlockPos pos : locations) {
                        Waypoint wp = new Waypoint.Builder()
                            .name(itemId + " storage")
                            .pos(pos)
                            .dimension(PlayerUtils.getDimension())
                            .build();
                         // Note: We are creating "phantom" waypoints here, not adding them to Waypoints.get() list proper?
                         // The Route system references waypoints. If we don't add them to Waypoints.get(), they might not save/load correctly 
                         // if we only save UUIDs. Reference implementation in Route.java:153 saves UUIDs.
                         // So we MUST add them to Waypoints.get().
                         Waypoints.get().add(wp);
                         route.add(wp);
                    }
                    
                    route.optimize();
                    Waypoints.get().routes.add(route);
                    Waypoints.get().save();
                    
                    info("Generated route (highlight)%s(default) with %d stops.", name, locations.size());
                    info("Distance: %.1fm (%s)", route.getTotalDistance(), route.getEstimatedTime());
                    
                    return SINGLE_SUCCESS;
                }))
            )
        );
    }
}
