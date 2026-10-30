package com.tridentxero.xweathermod.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side options (this player's machine only). File: config/xweathermod-client.toml
 * Also editable in game: Mods -> XWeather Mod -> Config.
 */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** How storm clouds, wall clouds and tornado funnels are drawn. */
    public enum CloudMode {
        /** Billboard puffs (the original look). Cheapest, works everywhere. */
        PARTICLES,
        /** One ray-marched volume shader draws the whole storm. Needs a GPU that can run a heavy fragment shader. */
        VOLUMETRIC
    }

    // ---- Clouds ----
    public static final ModConfigSpec.EnumValue<CloudMode> CLOUD_MODE = BUILDER
            .comment("How storm clouds, wall clouds and tornado funnels are drawn.",
                    "PARTICLES = billboard puffs (default, cheap).",
                    "VOLUMETRIC = a ray-marched volume shader draws the whole storm (heavier on the GPU).",
                    "VOLUMETRIC draws after translucent particles, so it works whatever the Clouds video setting is.",
                    "If the volumetric pass fails or never runs, the mod falls back to PARTICLES by itself.")
            .defineEnum("clouds.mode", CloudMode.PARTICLES);

    public static final ModConfigSpec.IntValue VOLUMETRIC_STEPS = BUILDER
            .comment("VOLUMETRIC only: how many samples each pixel takes through the storm. More = smoother and less grain,",
                    "but slower. Lower this first if the frame rate drops.")
            .defineInRange("clouds.volumetric.steps", 56, 24, 160);

    public static final ModConfigSpec.BooleanValue VOLUMETRIC_HIDE_VANILLA = BUILDER
            .comment("VOLUMETRIC only: while a volumetric storm is on screen, switch the vanilla Clouds video setting to Off,",
                    "then put it back as it was when the storm is gone, you leave the world, or the mode changes.",
                    "If you change the Clouds setting yourself meanwhile, the mod leaves your choice alone.")
            .define("clouds.volumetric.hideVanillaClouds", true);

    public static final ModConfigSpec.IntValue VOLUMETRIC_DISTANCE = BUILDER
            .comment("VOLUMETRIC only: storms farther than this many blocks are not drawn.")
            .defineInRange("clouds.volumetric.maxDistance", 6000, 500, 16000);

    public static final ModConfigSpec.DoubleValue VOLUMETRIC_DENSITY = BUILDER
            .comment("VOLUMETRIC only: how thick the cloud looks. 1.0 is the default, lower is wispier, higher is more opaque.")
            .defineInRange("clouds.volumetric.density", 1.0, 0.3, 2.5);

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

    public static final ModConfigSpec.DoubleValue FUNNEL_PARTICLE_SCALE = BUILDER
            .comment("Size of each funnel puff. 1.0 is the default (small, tight puffs), 0.5 is half the size.")
            .defineInRange("funnel.particleScale", 1.0, 0.2, 3.0);

    public static final ModConfigSpec.IntValue FUNNEL_MAX_PARTICLES = BUILDER
            .comment("Maximum funnel puffs alive at once, across all tornadoes. Lower this if the game stutters near a tornado.",
                    "0 disables funnels entirely.")
            .defineInRange("funnel.particleBudget", 12000, 0, 60000);

    public static final ModConfigSpec.IntValue FUNNEL_MAX_DISTANCE = BUILDER
            .comment("Funnels are only drawn for tornadoes within this many blocks of you.")
            .defineInRange("funnel.maxDistance", 900, 128, 2000);

    // ---- Flying debris (when tornadoes tear out blocks) ----
    public static final ModConfigSpec.BooleanValue DEBRIS_ENABLED = BUILDER
            .comment("Show blocks torn out by tornadoes as debris orbiting the funnel (only happens when the server",
                    "has terrain.enabled on).")
            .define("debris.enabled", true);

    public static final ModConfigSpec.IntValue DEBRIS_MAX_PIECES = BUILDER
            .comment("Most chunks orbiting tornadoes at once. Each one leaves a trail of block fragments, so lower this",
                    "if the game stutters.")
            .defineInRange("debris.maxPieces", 150, 0, 600);

    // ---- Sound ----
    public static final ModConfigSpec.BooleanValue SOUND_ENABLED = BUILDER
            .comment("Weather sounds: rain, wind gusts and the tornado roar.")
            .define("sound.enabled", true);

    public static final ModConfigSpec.DoubleValue SOUND_VOLUME = BUILDER
            .comment("Overall volume of this mod's weather sounds. 1.0 is the default.")
            .defineInRange("sound.volume", 1.0, 0.0, 2.0);

    // ---- Debug ----
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Print periodic storm, cloud and rain status lines to the game log. Off for normal play.")
            .define("debug.logging", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }
}
