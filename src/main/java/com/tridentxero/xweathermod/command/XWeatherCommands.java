package com.tridentxero.xweathermod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.tridentxero.xweathermod.Config;
import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.storm.LightningSimulator;
import com.tridentxero.xweathermod.storm.StormCell;
import com.tridentxero.xweathermod.storm.StormSimulator;
import com.tridentxero.xweathermod.storm.WindField;
import com.tridentxero.xweathermod.storm.TornadoCell;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /xweather spawn tornado <windspeed> <size>
 *   windspeed: peak wind in mph, 40-300. Picks the EF-inspired gameplay tier (not a scientific rating).
 *   size: tornado radius in blocks, 4-150.
 * /xweather spawn supercell build_to_tornado   (alias: build)
 *   A supercell that starts from nothing, builds up, and drops a tornado at about 40% of its life.
 * /xweather spawn supercell instant
 *   A supercell at full strength with its tornado already on the ground.
 *   Size, wind and lifetime come from the supercell.* settings in the common config.
 * /xweather clear
 *   Removes every storm and tornado in this dimension and pauses automatic spawning for 5 minutes.
 * /xweather lightning
 *   Strikes lightning at your position (visual only unless lightning.realStrikes is on).
 * /xweather wind
 *   Reports the wind at your position: speed, gust speed and the direction it blows from.
 * The tornado command also spawns a full-strength supercell above the tornado (if under the storm limit).
 * Spawns at the position of whoever runs it (player position, or the command block / console position).
 * Requires the "gamemaster" (op level 2) permission.
 *
 * UNVERIFIED against a compile:
 *  - Commands.hasPermission(Commands.LEVEL_GAMEMASTERS): per the 1.21.11 primer, Commands.LEVEL_* became
 *    PermissionChecks and hasPermission takes one. I have not confirmed the exact constant name in 26.1.2.
 *  - RegisterCommandsEvent package and getDispatcher().
 */
@EventBusSubscriber(modid = XWeatherMod.MOD_ID)
public final class XWeatherCommands {
    private XWeatherCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("xweather")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("wind").executes(XWeatherCommands::reportWind))
                .then(Commands.literal("lightning").executes(XWeatherCommands::strikeLightning))
                .then(Commands.literal("clear").executes(XWeatherCommands::clearWeather))
                .then(Commands.literal("spawn")
                        .then(Commands.literal("tornado")
                                .then(Commands.argument("windspeed", DoubleArgumentType.doubleArg(40.0, 300.0))
                                        .then(Commands.argument("size", DoubleArgumentType.doubleArg(4.0, 150.0))
                                                .executes(XWeatherCommands::spawnTornado))))
                        .then(Commands.literal("supercell")
                                .then(Commands.literal("build_to_tornado")
                                        .executes(ctx -> spawnSupercell(ctx, false)))
                                .then(Commands.literal("build")
                                        .executes(ctx -> spawnSupercell(ctx, false)))
                                .then(Commands.literal("instant")
                                        .executes(ctx -> spawnSupercell(ctx, true))))));
    }

    private static int clearWeather(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int[] removed = StormSimulator.clearAll(source.getLevel());
        String message = String.format("Cleared %d storm(s) and %d tornado(es) in this dimension. "
                + "Automatic weather is paused for 5 minutes.", removed[0], removed[1]);
        source.sendSuccess(() -> Component.literal(message), true);
        return removed[0] + removed[1];
    }

    private static int strikeLightning(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        double x = source.getPosition().x;
        double z = source.getPosition().z;
        if (!LightningSimulator.strikeAt(level, x, z)) {
            source.sendFailure(Component.literal("Could not strike here (chunk not loaded)."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(Config.LIGHTNING_REAL_STRIKES.get()
                ? "Lightning struck (real: can start fires and cause damage)"
                : "Lightning struck (visual only: lightning.realStrikes is off)"), true);
        return 1;
    }

    private static int reportWind(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!StormSimulator.isDimensionEnabled(level)) {
            source.sendFailure(Component.literal("Weather is disabled in this dimension (see the xweathermod config)."));
            return 0;
        }
        double x = source.getPosition().x;
        double z = source.getPosition().z;
        WindField.Wind wind = StormSimulator.windAt(level, x, z);
        String message = wind.speedMph() < 0.5
                ? "Wind: calm (no storm influence here)"
                : String.format("Wind: %d mph from the %s, gusting to %d mph",
                        Math.round(wind.speedMph()), wind.fromCompass(), Math.round(wind.gustSpeedMph()));
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int spawnSupercell(CommandContext<CommandSourceStack> context, boolean instant) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!StormSimulator.isDimensionEnabled(level)) {
            source.sendFailure(Component.literal("Weather is disabled in this dimension (see the xweathermod config)."));
            return 0;
        }
        double x = source.getPosition().x;
        double z = source.getPosition().z;
        StormCell storm = StormSimulator.spawnSupercell(level, x, z, instant);
        if (storm == null) {
            source.sendFailure(Component.literal("Storm limit reached for this dimension (storms.maxPerDimension). Wait for one to fade."));
            return 0;
        }
        String message = instant
                ? String.format("Spawned supercell #%d at %d, %d: full strength, tornado on the ground (if under the tornado limit)",
                        storm.id, (int) x, (int) z)
                : String.format("Spawned supercell #%d at %d, %d: building, tornado forms in about %.1f minutes",
                        storm.id, (int) x, (int) z, (storm.tornadoAtAge - storm.age) / 1200.0);
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int spawnTornado(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        double windspeed = DoubleArgumentType.getDouble(context, "windspeed");
        double size = DoubleArgumentType.getDouble(context, "size");
        ServerLevel level = source.getLevel();

        if (!StormSimulator.isDimensionEnabled(level)) {
            source.sendFailure(Component.literal("Weather is disabled in this dimension (see the xweathermod config)."));
            return 0;
        }

        double x = source.getPosition().x;
        double z = source.getPosition().z;
        TornadoCell tornado = StormSimulator.spawnTornado(level, x, z, windspeed, size);
        if (tornado == null) {
            source.sendFailure(Component.literal("Tornado limit reached for this dimension (tornadoes.maxPerDimension)."));
            return 0;
        }

        StormCell parent = StormSimulator.spawnSupercellOver(level, tornado);
        String parentText = parent != null ? " under supercell #" + parent.id
                : " (no supercell: storm limit reached, storms.maxPerDimension)";
        String message = String.format("Spawned %s tornado #%d: %d mph, radius %d blocks, at %d, %d%s (gameplay tier, not a damage rating)",
                tornado.tier().label, tornado.id, (int) windspeed, (int) size, (int) x, (int) z, parentText);
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }
}
