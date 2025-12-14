/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc.swarm;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Util;

import java.util.List;

public class Swarm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("What type of client to run.")
        .defaultValue(Mode.Host)
        .build()
    );

    private final Setting<String> ipAddress = sgGeneral.add(new StringSetting.Builder()
        .name("ip")
        .description("The IP address of the host server.")
        .defaultValue("localhost")
        .visible(() -> mode.get() == Mode.Worker)
        .build()
    );

    private final Setting<Integer> serverPort = sgGeneral.add(new IntSetting.Builder()
        .name("port")
        .description("The port used for connections.")
        .defaultValue(6969)
        .range(1, 65535)
        .noSlider()
        .build()
    );

    public final Setting<Boolean> requireToken = sgGeneral.add(new BoolSetting.Builder()
        .name("require-token")
        .description("If enabled, workers will only accept commands wrapped with the correct shared token.")
        .defaultValue(false)
        .build()
    );

    public final Setting<String> token = sgGeneral.add(new StringSetting.Builder()
        .name("token")
        .description("Shared token used to authenticate swarm commands when require-token is enabled.")
        .defaultValue("")
        .visible(requireToken::get)
        .build()
    );

    public final Setting<Boolean> requireTrustedHost = sgGeneral.add(new BoolSetting.Builder()
        .name("require-trusted-host")
        .description("If enabled, workers will only accept commands from trusted hosts (by IP/hostname). Default: ON (reject all until configured).")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Worker)
        .build()
    );

    public final Setting<List<String>> trustedHosts = sgGeneral.add(new StringListSetting.Builder()
        .name("trusted-hosts")
        .description("Trusted host IPs/hostnames allowed to control this worker (examples: localhost, 192.168.1.10).")
        .defaultValue()
        .visible(() -> mode.get() == Mode.Worker && requireTrustedHost.get())
        .build()
    );

    public final Setting<Boolean> allowUnsafeCommands = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-unsafe-commands")
        .description("If enabled, workers will execute any received 'swarm' command. If disabled, only an allowlisted subset is accepted.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> workerCommandsPerSecond = sgGeneral.add(new IntSetting.Builder()
        .name("worker-commands-per-second")
        .description("Worker-side rate limit for incoming swarm commands.")
        .defaultValue(10)
        .range(1, 100)
        .sliderRange(1, 30)
        .build()
    );

    public SwarmHost host;
    public SwarmWorker worker;

    public Swarm() {
        super(Categories.Misc, "swarm", "Allows you to control multiple instances of Meteor from one central host.");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WHorizontalList b = list.add(theme.horizontalList()).expandX().widget();

        WButton start = b.add(theme.button("Start")).expandX().widget();
        start.action = () -> {
            if (!isActive()) return;

            close();
            if (mode.get() == Mode.Host) host = new SwarmHost(serverPort.get());
            else worker = new SwarmWorker(ipAddress.get(), serverPort.get());
        };

        WButton stop = b.add(theme.button("Stop")).expandX().widget();
        stop.action = this::close;

        WButton guide = list.add(theme.button("Guide")).expandX().widget();
        guide.action = () -> Util.getOperatingSystem().open("https://github.com/MeteorDevelopment/meteor-client/wiki/Swarm-Guide");

        return list;
    }

    @Override
    public String getInfoString() {
        return mode.get().name();
    }

    @Override
    public void onActivate() {
        close();
    }

    @Override
    public void onDeactivate() {
        close();
    }

    public void close() {
        try {
            if (host != null) {
                host.disconnect();
                host = null;
            }
            if (worker != null) {
                worker.disconnect();
                worker = null;
            }
        } catch (Exception ignored) {}
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        toggle();
    }

    @EventHandler
    private void onGameJoin(GameJoinedEvent event) {
        toggle();
    }

    @Override
    public void toggle() {
        close();
        super.toggle();
    }

    public boolean isHost() {
        return mode.get() == Mode.Host && host != null && !host.isInterrupted();
    }

    public boolean isWorker() {
        return mode.get() == Mode.Worker && worker != null && !worker.isInterrupted();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (isWorker()) worker.tick();
    }

    public enum Mode {
        Host,
        Worker
    }
}
