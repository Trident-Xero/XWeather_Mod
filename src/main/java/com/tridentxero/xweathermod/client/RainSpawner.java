package com.tridentxero.xweathermod.client;

import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.storm.ClientStormState;
import com.tridentxero.xweathermod.storm.StormSnapshot;
import com.tridentxero.xweathermod.storm.WindField;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client only. Creates storm rain around the player.
 *
 * How it works:
 *  - Rain strength is the strongest storm covering the player, fading out over the outer 30% of the storm's
 *    radius, then eased over time so walking into or out of a storm changes the rain gradually.
 *  - Drops are created in a disc around the player, a few blocks above head height. For every drop column the
 *    surface height is read from the world's MOTION_BLOCKING heightmap (roofs, leaves and water count). Columns
 *    whose surface is above the spawn height get no rain, and each drop lives exactly until it reaches the
 *    surface. So rain falls on roofs and ground but never inside buildings, and indoor players see it only
 *    through windows or openings.
 *  - Heightmap reads are the exposure check; they are O(1) per drop and need no caching at this drop budget.
 *
 * Not in this build yet: snow in cold biomes, rain splashes, rain sounds, wind-tilted streaks, fog.
 *
 * UNVERIFIED against a compile: Level#getHeight(Heightmap.Types, int, int) and the Heightmap.Types.MOTION_BLOCKING
 * constant in 26.1.2.
 */
@EventBusSubscriber(modid = XWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class RainSpawner {
    /** Drops created per tick at full strength and density 1.0. */
    private static final double DROPS_PER_TICK = 70.0;
    private static final int MAX_DROPS_PER_TICK = 120;
    /** Blocks per tick of sideways drift per mph of wind (1 mph is about 0.0224 blocks per tick). */
    private static final double BLOCKS_PER_TICK_PER_MPH = 0.0224;
    private static final double MAX_DRIFT_BLOCKS_PER_TICK = 0.45;
    private static final double STALE_TICKS = 100.0;
    private static final int DEBUG_LOG_INTERVAL_TICKS = 100;

    private static ClientLevel lastLevel = null;
    private static float rainLevel = 0.0F;
    private static double remainder = 0.0;
    private static int tickCounter = 0;

    private RainSpawner() {
    }

    /** Current smoothed rain strength, 0..1. Used by later features (sound, sky darkening). */
    public static float rainLevel() {
        return rainLevel;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;

        if (level != lastLevel) {
            lastLevel = level;
            RainParticle.LIVE.set(0);
            rainLevel = 0.0F;
            remainder = 0.0;
            tickCounter = 0;
        }
        if (level == null || player == null || mc.isPaused()) {
            return;
        }
        tickCounter++;

        // --- Strength of rain at the player's position ---
        double elapsedTicks = (System.nanoTime() - ClientStormState.lastUpdateNanos()) / 50_000_000.0;
        float target = 0.0F;
        if (elapsedTicks <= STALE_TICKS) {
            for (StormSnapshot s : ClientStormState.storms()) {
                double cx = s.x() + s.vx() * elapsedTicks;
                double cz = s.z() + s.vz() * elapsedTicks;
                double d = Math.sqrt((cx - player.getX()) * (cx - player.getX())
                        + (cz - player.getZ()) * (cz - player.getZ()));
                double edge = Math.max(1.0, s.radius() * 0.3);
                float coverage = (float) Math.max(0.0, Math.min(1.0, (s.radius() - d) / edge));
                float strength = coverage * s.intensity();
                if (strength > target) {
                    target = strength;
                }
            }
        }
        // Ease toward the target so crossing a storm edge is gradual (about 2-3 seconds).
        rainLevel += (target - rainLevel) * 0.05F;
        if (target == 0.0F && rainLevel < 0.01F) {
            rainLevel = 0.0F;
        }

        if (ClientConfig.DEBUG_LOGGING.get() && tickCounter % DEBUG_LOG_INTERVAL_TICKS == 0) {
            XWeatherMod.LOGGER.info("[rain] target={}, level={}, drops alive={}",
                    String.format("%.2f", target), String.format("%.2f", rainLevel), RainParticle.LIVE.get());
        }

        if (!ClientConfig.RAIN_ENABLED.get() || rainLevel <= 0.0F) {
            return;
        }
        int maxDrops = ClientConfig.RAIN_MAX_DROPS.get();
        SpriteSet sprites = RainParticleProvider.SPRITES;
        if (maxDrops <= 0 || sprites == null) {
            return;
        }

        // --- Create drops ---
        remainder += rainLevel * DROPS_PER_TICK * ClientConfig.RAIN_DENSITY.get();
        int count = Math.min(MAX_DROPS_PER_TICK, (int) remainder);
        remainder -= count;
        remainder = Math.min(remainder, 1.0);

        RandomSource random = level.getRandom();
        double radius = ClientConfig.RAIN_RADIUS.get();
        // Wind at the player drives the sideways drift (same model the server uses for /xweather wind).
        WindField.Wind wind = WindField.sample(ClientStormState.storms(), ClientStormState.tornadoes(),
                elapsedTicks, level.getGameTime(), player.getX(), player.getZ());
        double steady = wind.speedMph();
        double driftBlocks = Math.min(MAX_DRIFT_BLOCKS_PER_TICK, BLOCKS_PER_TICK_PER_MPH * wind.gustSpeedMph())
                * ClientConfig.RAIN_WIND_DRIFT.get();
        double driftX = steady > 1.0E-6 ? wind.x() / steady * driftBlocks : 0.0;
        double driftZ = steady > 1.0E-6 ? wind.z() / steady * driftBlocks : 0.0;

        for (int i = 0; i < count; i++) {
            if (RainParticle.LIVE.get() >= maxDrops) {
                return;
            }
            double angle = random.nextDouble() * Math.PI * 2.0;
            double r = 3.0 + (radius - 3.0) * Math.sqrt(random.nextDouble()); // none right on top of the camera
            double x = player.getX() + Math.cos(angle) * r;
            double z = player.getZ() + Math.sin(angle) * r;
            double y = player.getY() + 6.0 + random.nextDouble() * 8.0;

            // Exposure check: where does this column's surface sit?
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            if (surface >= y - 1.0) {
                continue; // covered or too high: no rain here
            }
            int lifetime = (int) Math.ceil((y - surface) / RainParticle.FALL_SPEED);
            // With wind the drop lands sideways of where it started, so check the surface at the landing column
            // and use that one: rain must not be cut off by (or fall through) a roof it drifts onto.
            int landX = (int) Math.floor(x + driftX * lifetime);
            int landZ = (int) Math.floor(z + driftZ * lifetime);
            int landSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, landX, landZ);
            if (landSurface >= y - 1.0) {
                continue;
            }
            lifetime = (int) Math.ceil((y - landSurface) / RainParticle.FALL_SPEED);
            if (lifetime > 120) {
                continue; // very deep drop (e.g. over a big pit): skip, not worth the budget
            }
            mc.particleEngine.add(new RainParticle(level, x, y, z, driftX, driftZ, lifetime, sprites, random));
        }
    }
}
