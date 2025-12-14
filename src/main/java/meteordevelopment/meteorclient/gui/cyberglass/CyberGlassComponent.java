/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.cyberglass;

import net.minecraft.util.math.MathHelper;

public abstract class CyberGlassComponent {
    protected static double animate(double value, double target, double delta, double speed) {
        // Exponential-like smoothing without allocations.
        double step = delta * speed;
        if (step <= 0) return value;

        if (value < target) return Math.min(target, value + step);
        if (value > target) return Math.max(target, value - step);
        return value;
    }

    protected static double clamp01(double value) {
        return MathHelper.clamp(value, 0, 1);
    }
}
