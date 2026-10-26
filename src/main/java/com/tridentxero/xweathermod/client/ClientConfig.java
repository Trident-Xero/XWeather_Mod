package com.tridentxero.xweathermod.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side options (this player's machine only). File: config/xweathermod-client.toml
 * Also editable in game: Mods -> XWeather Mod -> Config.
 */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---- Clouds ----
    public static final ModConfigSpec.IntValue MAX_PUFFS = BUILDER
            .comment("Maximum number of cloud puffs alive at once. Lower this if the game stutters during storms.",
                    "0 disables the storm clouds entirely.")
            .defineInRange("clouds.maxPuffs", 1500, 0, 6000);

    public static final ModConfigSpec.DoubleValue DENSITY = BUILDER
            .comment("How thickly clouds form. 1.0 is the default look, 0.5 is half as many puffs, 2.0 is twice as many.")
            .defineInRange("clouds.density", 1.0, 0.1, 3.0);

    public static final ModConfigSpec.IntValue ALTITUDE = BUILDER
            .comment("Height of the storm cloud layer, in blocks.")
            .defineInRange("clouds.altitude", 185, 64, 320);

    public static final ModConfigSpec.IntValue MAX_SPAWN_DISTANCE = BUILDER
            .comment("Clouds are only created within this many blocks of you. Lower = cheaper, shorter visible range.")
            .defineInRange("clouds.maxSpawnDistance", 700, 128, 2000);

    // ---- Rain ----
    public static final ModConfigSpec.BooleanValue RAIN_ENABLED = BUILDER
            .comment("Show storm rain when you are under a storm.")
            .define("rain.enabled", true);

    public static final ModConfigSpec.DoubleValue RAIN_DENSITY = BUILDER
            .comment("How heavy the rain looks. 1.0 is the default, 0.5 is half as many drops, 2.0 is twice as many.")
            .defineInRange("rain.density", 1.0, 0.1, 3.0);

    public static final ModConfigSpec.IntValue RAIN_MAX_DROPS = BUILDER
            .comment("Maximum raindrops alive at once. Lower this if the game stutters in heavy rain.",
                    "0 disables the rain entirely.")
            .defineInRange("rain.maxDrops", 2500, 0, 8000);

    public static final ModConfigSpec.DoubleValue RAIN_WIND_DRIFT = BUILDER
            .comment("How strongly wind pushes rain sideways. 0 = rain always falls straight down, 1.0 = default.")
            .defineInRange("rain.windDrift", 1.0, 0.0, 2.0);

    public static final ModConfigSpec.IntValue RAIN_RADIUS = BUILDER
            .comment("Rain is created within this many blocks of you.")
            .defineInRange("rain.radius", 24, 8, 48);

    public static final ModConfigSpec.BooleanValue RAIN_INVERT_TILT = BUILDER
            .comment("Flip the lean direction of slanted rain. Turn this on if the streaks lean the wrong way",
                    "relative to the wind (they should lean in the direction the rain is travelling).")
            .define("rain.invertTilt", false);

    // ---- Tornado funnels ----
    public static final ModConfigSpec.BooleanValue FUNNEL_ENABLED = BUILDER
            .comment("Draw tornado funnels.")
            .define("funnel.enabled", true);

    public static final ModConfigSpec.DoubleValue FUNNEL_DENSITY = BUILDER
            .comment("How thick the funnel looks. 1.0 is the default, 0.5 is half as many puffs, 2.0 is twice as many.")
            .defineInRange("funnel.density", 1.0, 0.1, 3.0);

    public static final ModConfigSpec.IntValue FUNNEL_MAX_PARTICLES = BUILDER
            .comment("Maximum funnel puffs alive at once, across all tornadoes. Lower this if the game stutters near a tornado.",
                    "0 disables funnels entirely.")
            .defineInRange("funnel.maxParticles", 2000, 0, 8000);

    public static final ModConfigSpec.IntValue FUNNEL_MAX_DISTANCE = BUILDER
            .comment("Funnels are only drawn for tornadoes within this many blocks of you.")
            .defineInRange("funnel.maxDistance", 900, 128, 2000);

    // ---- Debug ----
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Print periodic storm, cloud and rain status lines to the game log. Off for normal play.")
            .define("debug.logging", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }
}
