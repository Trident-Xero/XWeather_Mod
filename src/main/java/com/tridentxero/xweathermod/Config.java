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
            .comment("Lifetime of a supercell in minutes. Make it longer than supercell.buildMaxSeconds plus the tornado duration.")
            .defineInRange("supercell.durationMinutes", 20, 4, 120);

    public static final ModConfigSpec.DoubleValue SUPERCELL_BUILD_MIN_SECONDS = BUILDER
            .comment("'build_to_tornado' supercells drop their tornado between this many seconds (earliest)...")
            .defineInRange("supercell.buildMinSeconds", 30.0, 10.0, 1800.0);

    public static final ModConfigSpec.DoubleValue SUPERCELL_BUILD_MAX_SECONDS = BUILDER
            .comment("...and this many seconds (latest) after they start. The exact time is random in that range.")
            .defineInRange("supercell.buildMaxSeconds", 50.0, 10.0, 1800.0);

    public static final ModConfigSpec.IntValue SUPERCELL_TORNADO_WIND_MPH = BUILDER
            .comment("Peak wind in mph of the tornado a supercell produces (natural maximum 313).")
            .defineInRange("supercell.tornadoWindMph", 130, 40, 313);

    public static final ModConfigSpec.IntValue SUPERCELL_TORNADO_SIZE = BUILDER
            .comment("Radius in blocks of the tornado a supercell produces (natural maximum 4200).")
            .defineInRange("supercell.tornadoSize", 40, 4, 4200);

    // ---- Lightning ----
    public static final ModConfigSpec.BooleanValue LIGHTNING_ENABLED = BUILDER
            .comment("Storms produce lightning and thunder.")
            .define("lightning.enabled", true);

    public static final ModConfigSpec.DoubleValue LIGHTNING_STRIKES_PER_MINUTE = BUILDER
            .comment("Strikes per minute for one full-strength 200-block storm near a player. Bigger storms strike more,",
                    "supercells 1.5x. 0 turns lightning off.")
            .defineInRange("lightning.strikesPerMinute", 8.0, 0.0, 120.0);

    public static final ModConfigSpec.IntValue LIGHTNING_MAX_DISTANCE = BUILDER
            .comment("Bolts only strike within this many blocks of a player (and only in loaded chunks).")
            .defineInRange("lightning.maxDistanceFromPlayer", 120, 30, 256);

    public static final ModConfigSpec.BooleanValue LIGHTNING_REAL_STRIKES = BUILDER
            .comment("false (default): bolts are visual and audible only. No fires, no damage, no charged creepers.",
                    "true: vanilla bolts that set fires and hurt entities. Separate from the tornado terrain damage option.")
            .define("lightning.realStrikes", false);

    // ---- Entity forces (wind and tornado motion) ----
    public static final ModConfigSpec.BooleanValue FORCES_ENABLED = BUILDER
            .comment("Wind and tornadoes push, pull and lift entities. Motion only: no direct damage, no block changes.")
            .define("forces.enabled", true);

    public static final ModConfigSpec.BooleanValue FORCES_AFFECT_PLAYERS = BUILDER
            .comment("Also push players (creative and spectator players are never affected).")
            .define("forces.affectPlayers", true);

    public static final ModConfigSpec.BooleanValue FORCES_STORM_WIND = BUILDER
            .comment("Strong storm wind (40+ mph) gently pushes exposed entities near players, even outside a tornado.")
            .define("forces.stormWind", true);

    public static final ModConfigSpec.DoubleValue FORCES_STRENGTH = BUILDER
            .comment("Overall strength multiplier for all entity forces. 0.5 is half as strong, 2.0 twice.")
            .defineInRange("forces.strength", 1.0, 0.0, 5.0);

    public static final ModConfigSpec.IntValue FORCES_MAX_ENTITIES = BUILDER
            .comment("Most entities moved per tick in one dimension. Lower this on busy servers.")
            .defineInRange("forces.maxEntitiesPerTick", 200, 10, 2000);

    // ---- Terrain damage (separate from entity forces; OFF by default) ----
    public static final ModConfigSpec.BooleanValue TERRAIN_ENABLED = BUILDER
            .comment("Allow tornadoes to remove blocks. OFF by default. Has no claim or spawn-protection integration yet,",
                    "so leave it off on servers that rely on those. Entity forces are a separate setting.")
            .define("terrain.enabled", false);

    public static final ModConfigSpec.IntValue TERRAIN_MIN_TIER = BUILDER
            .comment("Lowest tornado tier that removes blocks: 0=EF0, 1=EF1, 2=EF2, 3=EF3, 4=EF4, 5=EF5.",
                    "Tiers are gameplay categories from the tornado's wind speed, not a damage rating.")
            .defineInRange("terrain.minTier", 2, 0, 5);

    public static final ModConfigSpec.IntValue TERRAIN_HITS_PER_STEP = BUILDER
            .comment("Bites one tornado lands on individual blocks per simulation step (10 ticks by default).",
                    "A bite takes 10% of a block at 65 mph up to 60% at 201 mph (less for harder blocks).")
            .defineInRange("terrain.hitsPerStep", 24, 1, 200);

    public static final ModConfigSpec.IntValue TERRAIN_MAX_BLOCKS_PER_STEP = BUILDER
            .comment("Most blocks one tornado can fully break per simulation step. Lower is gentler and cheaper.",
                    "Broken blocks never drop items.")
            .defineInRange("terrain.maxBlocksPerStep", 40, 1, 500);

    static final ModConfigSpec SPEC = BUILDER.build();
}
