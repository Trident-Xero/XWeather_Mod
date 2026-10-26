package com.tridentxero.xweathermod;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Stage 1a configuration. Uses the same ModConfigSpec API as the MDK template.
 * File: config/xweathermod-common.toml
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---- General ----
    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch for the weather simulation.")
            .define("enabled", true);

    public static final ModConfigSpec.BooleanValue TEST_MODE = BUILDER
            .comment("Stage 1 testing: whenever a dimension has players but no storm, spawn one near a player.",
                    "Turn this off to use the random spawn chance below instead.")
            .define("testMode", true);

    // ---- Dimensions ----
    public static final ModConfigSpec.BooleanValue OVERWORLD_ENABLED = BUILDER
            .comment("Allow storms in the Overworld.")
            .define("dimensions.overworld", true);
    public static final ModConfigSpec.BooleanValue NETHER_ENABLED = BUILDER
            .comment("Allow storms in the Nether (not recommended).")
            .define("dimensions.nether", false);
    public static final ModConfigSpec.BooleanValue END_ENABLED = BUILDER
            .comment("Allow storms in the End (not recommended).")
            .define("dimensions.end", false);

    // ---- Simulation ----
    public static final ModConfigSpec.IntValue SIM_INTERVAL_TICKS = BUILDER
            .comment("How often the storm simulation steps, in ticks. Higher = cheaper, coarser movement.")
            .defineInRange("simulation.intervalTicks", 10, 1, 200);

    public static final ModConfigSpec.IntValue SYNC_INTERVAL_TICKS = BUILDER
            .comment("How often storm state is sent to players, in ticks.")
            .defineInRange("simulation.syncIntervalTicks", 20, 5, 200);

    public static final ModConfigSpec.IntValue MAX_STORMS = BUILDER
            .comment("Maximum simultaneous storms per dimension.")
            .defineInRange("storms.maxPerDimension", 4, 0, 64);

    public static final ModConfigSpec.DoubleValue SPAWN_CHANCE_PER_MINUTE = BUILDER
            .comment("Chance per real minute (of simulated time) that a new storm forms near a player when testMode is off.")
            .defineInRange("storms.spawnChancePerMinute", 0.15, 0.0, 1.0);

    public static final ModConfigSpec.IntValue RADIUS_MIN = BUILDER
            .comment("Minimum storm radius in blocks.")
            .defineInRange("storms.radiusMin", 120, 16, 2000);
    public static final ModConfigSpec.IntValue RADIUS_MAX = BUILDER
            .comment("Maximum storm radius in blocks.")
            .defineInRange("storms.radiusMax", 300, 16, 2000);

    public static final ModConfigSpec.DoubleValue SPEED_BLOCKS_PER_SECOND = BUILDER
            .comment("Storm travel speed in blocks per second.")
            .defineInRange("storms.speedBlocksPerSecond", 3.0, 0.1, 50.0);

    public static final ModConfigSpec.IntValue DURATION_MIN_MINUTES = BUILDER
            .comment("Minimum storm lifetime in minutes.")
            .defineInRange("storms.durationMinMinutes", 8, 1, 600);
    public static final ModConfigSpec.IntValue DURATION_MAX_MINUTES = BUILDER
            .comment("Maximum storm lifetime in minutes.")
            .defineInRange("storms.durationMaxMinutes", 20, 1, 600);

    public static final ModConfigSpec.IntValue SPAWN_DISTANCE_MIN = BUILDER
            .comment("Minimum distance from the chosen player at which a storm forms, in blocks.")
            .defineInRange("storms.spawnDistanceMin", 300, 32, 5000);
    public static final ModConfigSpec.IntValue SPAWN_DISTANCE_MAX = BUILDER
            .comment("Maximum distance from the chosen player at which a storm forms, in blocks.")
            .defineInRange("storms.spawnDistanceMax", 500, 32, 5000);

    public static final ModConfigSpec.IntValue MAX_CATCH_UP_MINUTES = BUILDER
            .comment("When a dimension was not ticking (server offline, no players), storms are advanced by the",
                    "elapsed game time when it resumes, up to this many minutes. Anything beyond is ignored.")
            .defineInRange("persistence.maxCatchUpMinutes", 30, 0, 600);

    // ---- Tornadoes (spawned by /xweather spawn tornado) ----
    public static final ModConfigSpec.IntValue MAX_TORNADOES = BUILDER
            .comment("Maximum simultaneous tornadoes per dimension.")
            .defineInRange("tornadoes.maxPerDimension", 8, 0, 64);

    public static final ModConfigSpec.DoubleValue TORNADO_SPEED_BLOCKS_PER_SECOND = BUILDER
            .comment("Tornado travel speed in blocks per second (random direction when spawned by command).")
            .defineInRange("tornadoes.speedBlocksPerSecond", 2.0, 0.0, 30.0);

    public static final ModConfigSpec.IntValue TORNADO_DURATION_MINUTES = BUILDER
            .comment("Lifetime of a tornado in minutes.")
            .defineInRange("tornadoes.durationMinutes", 4, 1, 120);

    // ---- Supercells (spawned by /xweather spawn supercell ...) ----
    public static final ModConfigSpec.IntValue SUPERCELL_RADIUS = BUILDER
            .comment("Radius in blocks of a supercell storm.")
            .defineInRange("supercell.radius", 350, 100, 1000);

    public static final ModConfigSpec.IntValue SUPERCELL_DURATION_MINUTES = BUILDER
            .comment("Lifetime of a supercell in minutes. In 'build_to_tornado' mode the tornado forms at about 40% of this.")
            .defineInRange("supercell.durationMinutes", 20, 4, 120);

    public static final ModConfigSpec.IntValue SUPERCELL_TORNADO_WIND_MPH = BUILDER
            .comment("Peak wind in mph of the tornado a supercell produces.")
            .defineInRange("supercell.tornadoWindMph", 130, 40, 300);

    public static final ModConfigSpec.IntValue SUPERCELL_TORNADO_SIZE = BUILDER
            .comment("Radius in blocks of the tornado a supercell produces.")
            .defineInRange("supercell.tornadoSize", 40, 4, 150);

    static final ModConfigSpec SPEC = BUILDER.build();
}
