package com.tridentxero.xweathermod.client;

import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.storm.ClientStormState;
import com.tridentxero.xweathermod.storm.StormSnapshot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client only. Keeps cloud puffs alive over each synced storm, within the budget set in {@link ClientConfig}.
 * Puff position is extrapolated from the last server snapshot (position + velocity * time since sync),
 * so storms glide smoothly between the 1-second syncs.
 *
 * Puffs are created here and added to the particle engine directly. Going through
 * Level#addAlwaysVisibleParticle did not work for this mod's particle types in 26.1.2 (the game never called
 * the provider), so this path is deliberate.
 *
 * UNVERIFIED-in-theory but confirmed in play on 26.1.2: ClientTickEvent.Post, Minecraft#particleEngine,
 * ParticleEngine#add(Particle).
 */
@EventBusSubscriber(modid = XWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class CloudSpawner {
    private static final double PUFFS_PER_TICK_PER_100_BLOCKS = 0.15;
    private static final int MAX_SPAWNS_PER_STORM_PER_TICK = 6;
    /** If no sync arrived for this many ticks, stop spawning (stale data). */
    private static final double STALE_TICKS = 100.0;
    private static final int DEBUG_LOG_INTERVAL_TICKS = 100;

    private static ClientLevel lastLevel = null;
    private static double spawnRemainder = 0.0;
    private static int tickCounter = 0;

    private CloudSpawner() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;

        if (level != lastLevel) {
            // New world/dimension: old puffs are gone, reset the budget and forget stale storms.
            lastLevel = level;
            CloudParticle.LIVE.set(0);
            ClientStormState.clear();
            spawnRemainder = 0.0;
            tickCounter = 0;
        }
        if (level == null || player == null || mc.isPaused()) {
            return;
        }

        tickCounter++;
        double elapsedTicks = (System.nanoTime() - ClientStormState.lastUpdateNanos()) / 50_000_000.0;
        if (ClientConfig.DEBUG_LOGGING.get() && tickCounter % DEBUG_LOG_INTERVAL_TICKS == 0) {
            logDebug(player, elapsedTicks);
        }

        int maxPuffs = ClientConfig.MAX_PUFFS.get();
        if (maxPuffs <= 0 || ClientStormState.storms().isEmpty() || elapsedTicks > STALE_TICKS) {
            return;
        }

        double density = ClientConfig.DENSITY.get();
        double altitude = ClientConfig.ALTITUDE.get();
        double maxDistance = ClientConfig.MAX_SPAWN_DISTANCE.get();
        RandomSource random = level.getRandom();

        for (StormSnapshot storm : ClientStormState.storms()) {
            if (CloudParticle.LIVE.get() >= maxPuffs) {
                return;
            }
            double cx = storm.x() + storm.vx() * elapsedTicks;
            double cz = storm.z() + storm.vz() * elapsedTicks;
            double dxp = cx - player.getX();
            double dzp = cz - player.getZ();
            double distToCenter = Math.sqrt(dxp * dxp + dzp * dzp);
            if (distToCenter > storm.radius() + maxDistance) {
                continue; // whole storm is too far away to matter
            }

            double rate = storm.intensity() * (storm.radius() / 100.0) * PUFFS_PER_TICK_PER_100_BLOCKS * density;
            spawnRemainder += rate;
            int count = Math.min(MAX_SPAWNS_PER_STORM_PER_TICK, (int) spawnRemainder);
            spawnRemainder -= count;

            for (int i = 0; i < count; i++) {
                if (CloudParticle.LIVE.get() >= maxPuffs) {
                    return;
                }
                double r = storm.radius() * Math.sqrt(random.nextDouble());
                double angle = random.nextDouble() * Math.PI * 2.0;
                double px = cx + Math.cos(angle) * r;
                double pz = cz + Math.sin(angle) * r;
                double ddx = px - player.getX();
                double ddz = pz - player.getZ();
                if (ddx * ddx + ddz * ddz > maxDistance * maxDistance) {
                    continue;
                }
                double py = altitude + (random.nextDouble() - 0.5) * 16.0;

                // Dark, banded core for strong storms; soft light puffs around the edges.
                boolean core = (r / storm.radius()) < 0.55 && storm.intensity() > 0.35F;
                double darkness = storm.intensity() * (core ? 1.0 : 0.6);
                spawnPuff(mc, level, core, px, py, pz, storm.vx(), darkness, storm.vz(), random);
            }
        }
        // Keep the carry-over from growing without bound when the budget is full.
        spawnRemainder = Math.min(spawnRemainder, 1.0);
    }

    private static void spawnPuff(Minecraft mc, ClientLevel level, boolean banded,
                                  double x, double y, double z, double xd, double darkness, double zd,
                                  RandomSource random) {
        SpriteSet sprites = banded ? CloudParticleProvider.BANDED_SPRITES : CloudParticleProvider.SOFT_SPRITES;
        if (sprites == null) {
            return; // providers not built yet
        }
        mc.particleEngine.add(new CloudParticle(level, x, y, z, xd, darkness, zd, sprites, random));
    }

    private static void logDebug(LocalPlayer player, double elapsedTicks) {
        int stormCount = ClientStormState.storms().size();
        String nearest = "n/a";
        if (stormCount > 0) {
            double best = Double.MAX_VALUE;
            for (StormSnapshot s : ClientStormState.storms()) {
                double cx = s.x() + s.vx() * elapsedTicks;
                double cz = s.z() + s.vz() * elapsedTicks;
                best = Math.min(best, Math.sqrt((cx - player.getX()) * (cx - player.getX())
                        + (cz - player.getZ()) * (cz - player.getZ())));
            }
            nearest = String.valueOf((int) best);
        }
        XWeatherMod.LOGGER.info("[clouds] storms known={}, nearest centre={} blocks, puffs alive={}, ticks since sync={}",
                stormCount, nearest, CloudParticle.LIVE.get(), (int) elapsedTicks);
    }
}
