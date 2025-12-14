/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.waypoints;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;

public class WaypointJsonAdapter extends TypeAdapter<Waypoint> {
    @Override
    public void write(JsonWriter out, Waypoint value) throws IOException {
        out.beginObject();
        out.name("name").value(value.name.get());
        out.name("icon").value(value.icon.get());
        out.name("color").value(value.color.get().toString());

        BlockPos pos = value.pos.get();
        out.name("x").value(pos.getX());
        out.name("y").value(pos.getY());
        out.name("z").value(pos.getZ());

        out.name("dimension").value(value.dimension.get().name());
        out.name("tags").value(value.tags.get());

        out.endObject();
    }

    @Override
    public Waypoint read(JsonReader in) throws IOException {
        String name = "Home";
        String icon = "Square";
        String tags = "";
        SettingColor color = new SettingColor(255, 255, 255);
        int x = 0, y = 64, z = 0;
        Dimension dimension = Dimension.Overworld;

        in.beginObject();
        while (in.hasNext()) {
            switch (in.nextName()) {
                case "name" -> name = in.nextString();
                case "icon" -> icon = in.nextString();
                case "color" -> parseColor(in.nextString(), color);
                case "x" -> x = in.nextInt();
                case "y" -> y = in.nextInt();
                case "z" -> z = in.nextInt();
                case "dimension" -> dimension = Dimension.valueOf(in.nextString());
                case "tags" -> tags = in.nextString();
                default -> in.skipValue();
            }
        }
        in.endObject();

        Waypoint w = new Waypoint.Builder()
                .name(name)
                .icon(icon)
                .pos(new BlockPos(x, y, z))
                .dimension(dimension)
                .tags(tags)
                .build();

        w.color.set(color);
        return w;
    }

    private void parseColor(String s, SettingColor color) {
        String[] split = s.split(" ");
        if (split.length >= 3) {
            color.r = Integer.parseInt(split[0]);
            color.g = Integer.parseInt(split[1]);
            color.b = Integer.parseInt(split[2]);
            if (split.length >= 4)
                color.a = Integer.parseInt(split[3]);
        }
    }
}
