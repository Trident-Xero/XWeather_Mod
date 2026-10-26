package com.tridentxero.xweathermod.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.storm.ClientStormState;
import com.tridentxero.xweathermod.storm.TornadoSnapshot;

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
 * Client only. Draws tornado funnels from the synced tornado data (no new network traffic).
 *
 * Each tick, for every tornado near the player, it:
 *  1. extrapolates the tornado's centre from its last snapshot and finds the ground height there
 *     (smoothed, so the funnel base does not jump);
 *  2. publishes that as a {@link TornadoView} that the {@link FunnelParticle}s read;
 *  3. creates new puffs. The funnel "descends" as the tornado intensifies: only the upper part exists at first,
 *     and it reaches the ground at about 60% intensity, at which point a ground dust ring appears.
 *
 * Funnel shape: radius = size * profile(h), narrow at the ground (0.3 x size) widening to 2 x size at the cloud.
 * Cost is bounded by funnel.maxParticles. Visual only: damage and forces are a later step.
 */
@EventBusSubscriber(modid = XWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class FunnelSpawner {
    private static final double STALE_TICKS = 100.0;
    private static final int MAX_SPAWNS_PER_TORNADO_PER_TICK = 40;

    /** Per-tornado state for the particles. Replaced wholesale each tick; reads never block. */
    public record TornadoView(double cx, double cz, double baseY, double topY, double size,
                              double windMph, double intensity, double time) {
    }

    private static volatile Map<Integer, TornadoView> views = Map.of();
    private static final Map<Integer, Double> smoothedBase = new HashMap<>();
    private static ClientLevel lastLevel = null;
    private static double spawnRemainder = 0.0;

    private FunnelSpawner() {
    }

    public static TornadoView view(int tornadoId) {
        return views.get(tornadoId);
    }

    /** Funnel radius as a multiple of the tornado's size at height fraction h (0 ground, 1 cloud). */
    public static float profile(float h) {
        return 0.3F + 1.7F * h * h;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;

        if (level != lastLevel) {
            lastLevel = level;
            views = Map.of();
            smoothedBase.clear();
            FunnelParticle.LIVE.set(0);
            spawnRemainder = 0.0;
        }
        if (level == null || player == null || mc.isPaused()) {
            return;
        }

        double elapsedTicks = (System.nanoTime() - ClientStormState.lastUpdateNanos()) / 50_000_000.0;
        List<TornadoSnapshot> tornadoes = ClientStormState.tornadoes();
        if (!ClientConfig.FUNNEL_ENABLED.get() || tornadoes.isEmpty() || elapsedTicks > STALE_TICKS) {
            views = Map.of();
            smoothedBase.clear();
            return;
        }

        double maxDist = ClientConfig.FUNNEL_MAX_DISTANCE.get();
        double cloudTop = ClientConfig.ALTITUDE.get() - 6.0;
        Map<Integer, TornadoView> next = new HashMap<>();
        double time = level.getGameTime();

        for (TornadoSnapshot t : tornadoes) {
            double cx = t.x() + t.vx() * elapsedTicks;
            double cz = t.z() + t.vz() * elapsedTicks;
            double dx = cx - player.getX();
            double dz = cz - player.getZ();
            if (dx * dx + dz * dz > maxDist * maxDist || t.intensity() < 0.02F) {
                continue;
            }
            // Ground height under the tornado. In unloaded client chunks the heightmap is not meaningful, so fall
            // back to the player's height for far-away tornadoes.
            double target = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(cx), (int) Math.floor(cz));
            if (target <= level.getMinY() + 1) {
                target = player.getY();
            }
            double base = smoothedBase.merge(t.id(), target, (old, now) -> old + (now - old) * 0.15);
            double top = Math.max(cloudTop, base + 60.0);
            next.put(t.id(), new TornadoView(cx, cz, base, top, t.size(), t.windMph(), t.intensity(), time));
        }
        smoothedBase.keySet().retainAll(next.keySet());
        views = next;

        SpriteSet sprites = CloudParticleProvider.SOFT_SPRITES;
        int maxParticles = ClientConfig.FUNNEL_MAX_PARTICLES.get();
        if (sprites == null || maxParticles <= 0) {
            return;
        }
        RandomSource random = level.getRandom();
        double density = ClientConfig.FUNNEL_DENSITY.get();

        for (TornadoSnapshot t : tornadoes) {
            TornadoView v = next.get(t.id());
            if (v == null) {
                continue;
            }
            double rate = 10.0 * v.intensity() * Math.sqrt(Math.max(4.0, v.size()) / 30.0) * density;
            spawnRemainder += rate;
            int count = Math.min(MAX_SPAWNS_PER_TORNADO_PER_TICK, (int) spawnRemainder);
            spawnRemainder -= count;

            // How far down the funnel has descended: 0 at no intensity, reaches the ground at 0.6.
            double reach = Math.max(0.0, Math.min(1.0, v.intensity() / 0.6));
            double lowest = 1.0 - reach;

            for (int i = 0; i < count; i++) {
                if (FunnelParticle.LIVE.get() >= maxParticles) {
                    return;
                }
                boolean dust = reach >= 1.0 && random.nextFloat() < 0.18F;
                float h = dust ? 0.0F : (float) (lowest + (1.0 - lowest) * random.nextDouble());
                double angle = random.nextDouble() * Math.PI * 2.0;
                double r = v.size() * profile(h) * (dust ? 1.8 : 1.0);
                double x = v.cx() + Math.cos(angle) * r;
                double z = v.cz() + Math.sin(angle) * r;
                double y = v.baseY() + h * (v.topY() - v.baseY());
                mc.particleEngine.add(new FunnelParticle(level, t.id(), x, y, z, h, dust, angle, sprites, random));
            }
        }
        spawnRemainder = Math.min(spawnRemainder, 1.0);
    }
}
