/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.command.CommandSource;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import baritone.api.pathing.goals.GoalBlock;

public class HomeCommand extends Command {
    public HomeCommand() {
        super("home", "Calculates a path to the 'home' waypoint.", "h");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            Waypoint home = Waypoints.get().get("Home");
            if (home == null)
                home = Waypoints.get().get("home");

            if (home == null) {
                error("No 'home' waypoint found. Use '(highlight).home set(default)' to set one.");
                return SINGLE_SUCCESS;
            }

            BlockPos pos = home.pos.get();
            info(net.minecraft.text.Text.literal("Pathing to Home at ")
                    .append(ChatUtils
                            .formatCoords(new net.minecraft.util.math.Vec3d(pos.getX(), pos.getY(), pos.getZ())))
                    .append("..."));

            Object baritone = BaritoneUtils.getPrimaryBaritone();
            if (baritone != null) {
                Object customGoalProcess = BaritoneUtils.getCustomGoalProcess(baritone);
                if (customGoalProcess != null) {
                    BaritoneUtils.setGoalAndPath(customGoalProcess, new GoalBlock(pos));
                }
            }

            return SINGLE_SUCCESS;
        });

        builder.then(literal("set").executes(context -> {
            if (mc.player == null)
                return -1;

            // Remove existing home if any
            Waypoint existing = Waypoints.get().get("Home");
            if (existing != null)
                Waypoints.get().remove(existing);
            existing = Waypoints.get().get("home");
            if (existing != null)
                Waypoints.get().remove(existing);

            BlockPos pos = mc.player.getBlockPos();
            Waypoint home = new Waypoint.Builder()
                    .name("Home")
                    .pos(pos)
                    .dimension(PlayerUtils.getDimension())
                    .build();

            Waypoints.get().add(home);
            Waypoints.get().save();

            info(net.minecraft.text.Text.literal("Home set to ")
                    .append(ChatUtils
                            .formatCoords(new net.minecraft.util.math.Vec3d(pos.getX(), pos.getY(), pos.getZ())))
                    .append("."));
            return SINGLE_SUCCESS;
        }));
    }
}
