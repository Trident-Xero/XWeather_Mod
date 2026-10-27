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
 * Client only. Draws tornado funnels from the synced tornado data (no new network traffic). Same particle texture
 * as before; only the placement model changed, to look more like a real tornado:
 *
 *  - Trumpet profile: a thin stalk at the ground that widens slowly, then flares sharply into the cloud base.
 *  - Three kinds of puff: BODY (the condensation funnel, a thin sheath), DUST (a low, wide, brown debris skirt
 *    around the base, only once the funnel touches down) and COLLAR (a dark, wide disc of larger puffs filling the
 *    cloud base where the funnel attaches).
 *  - The funnel leans backwards along the tornado's direction of travel and has a slow, kinked sway that is
 *    stronger while the tornado is weak. A weak or dying tornado is a thin rope that thickens as it strengthens.
 *  - It spins faster near the ground than at the top, and descends from the cloud as intensity rises.
 *  - Colour runs from dusty brown at the base through light grey in the middle to dark slate at the cloud.
 *
 * Each tick it publishes a {@link TornadoView} per nearby tornado for {@link FunnelParticle} and
 * {@link DebrisSpawner} to read. Cost is bounded by funnel.maxParticles. Visual only.
 */
@EventBusSubscriber(modid = XWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class FunnelSpawner {
    public static final int KIND_BODY = 0;
    public static final int KIND_DUST = 1;
    public static final int KIND_COLLAR = 2;

    private static final double STALE_TICKS = 100.0;
    private static final int MAX_SPAWNS_PER_TORNADO_PER_TICK = 110;

    /** Per-tornado state for the particles. Replaced wholesale each tick; reads never block. vx/vz in blocks per tick. */
    public record TornadoView(int id, double cx, double cz, double vx, double vz, double baseY, double topY,
                              double size, double windMph, double intensity, double time) {
    }

    private static volatile Map<Integer, TornadoView> views = Map.of();
    private static final Map<Integer, Double> smoothedBase = new HashMap<>();
    private static ClientLevel lastLevel = null;
    private static double spawnRemainder = 0.0;
    /** Per-tornado spawn counter driving the evenly spread (low-discrepancy) puff placement. */
    private static final Map<Integer, Long> sequence = new HashMap<>();

    private FunnelSpawner() {
    }

    public static TornadoView view(int tornadoId) {
        return views.get(tornadoId);
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    private static double smooth(double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return c * c * (3.0 - 2.0 * c);
    }

    /** Funnel radius as a multiple of the tornado's size at height fraction h (0 ground, 1 cloud): a trumpet. */
    public static double profile(double h) {
        return 0.16 + 0.22 * h + 1.25 * h * h * h * h * h;
    }

    /** 0.45 for a barely-there rope, up to 1.0 for a mature tornado. */
    public static double rope(TornadoView v) {
        return 0.45 + 0.55 * smooth(v.intensity() / 0.5);
    }

    /** Orbit radius in blocks. param: radial multiplier for the puff (jitter for BODY, spread for DUST and COLLAR). */
    public static double radiusAt(TornadoView v, double h, int kind, double param) {
        return switch (kind) {
            case KIND_DUST -> v.size() * (0.25 + param);
            case KIND_COLLAR -> v.size() * profile(1.0) * param;
            default -> v.size() * profile(h) * rope(v) * param;
        };
    }

    /**
     * World position of a puff at height fraction h, orbit angle and radius r, including the funnel's backward lean
     * and kinked sway. Used when creating a puff and every tick afterwards, so a new puff never jumps.
     */
    public static double[] place(TornadoView v, double h, double angle, double r, int kind) {
        double weak = 1.5 - 0.5 * smooth(v.intensity() / 0.5); // weak tornadoes lean and wander more
        double speed = Math.sqrt(v.vx() * v.vx() + v.vz() * v.vz());
        double bx = speed > 1.0E-9 ? -v.vx() / speed : 1.0; // backwards along travel
        double bz = speed > 1.0E-9 ? -v.vz() / speed : 0.0;
        double lean = v.size() * 0.9 * Math.pow(h, 1.6) * weak;
        double kink = Math.sin(v.time() * 0.02 + h * 5.0 + v.id() * 1.3) * v.size() * 0.25 * h * weak;
        double kinkAlong = Math.cos(v.time() * 0.017 + h * 4.0 + v.id() * 0.7) * v.size() * 0.15 * h * weak;
        double cx = v.cx() + bx * (lean + kinkAlong) - bz * kink;
        double cz = v.cz() + bz * (lean + kinkAlong) + bx * kink;
        double x = cx + Math.cos(angle) * r;
        double z = cz + Math.sin(angle) * r;
        double y = v.baseY() + h * (v.topY() - v.baseY()) + (kind == KIND_DUST ? 0.5 : 0.0);
        return new double[] {x, y, z};
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
            sequence.clear();
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
            sequence.clear();
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
            next.put(t.id(), new TornadoView(t.id(), cx, cz, t.vx(), t.vz(), base, top, t.size(), t.windMph(),
                    t.intensity(), time));
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
            double rate = 38.0 * v.intensity() * Math.sqrt(Math.max(4.0, v.size()) / 30.0) * density;
            // Never ask for more than the budget can sustain (average puff life is about 110 ticks). Staying under
            // the cap keeps the spawn rate steady, so the funnel gets thinner evenly instead of developing gaps.
            rate = Math.min(rate, maxParticles * 0.8 / 110.0 / Math.max(1, next.size()));
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
                float roll = random.nextFloat();
                int kind;
                if (reach >= 1.0 && roll < 0.18F) {
                    kind = KIND_DUST;
                } else if (roll < 0.28F) {
                    kind = KIND_COLLAR;
                } else {
                    kind = KIND_BODY;
                }

                double h;
                double angle;
                double param;
                if (kind == KIND_DUST) {
                    h = random.nextDouble() * 0.09;              // a low skirt, not a column
                    angle = random.nextDouble() * Math.PI * 2.0;
                    param = 0.05 + random.nextDouble() * 1.75;   // out to about 2 x size
                } else if (kind == KIND_COLLAR) {
                    h = 0.9 + random.nextDouble() * 0.1;
                    angle = random.nextDouble() * Math.PI * 2.0;
                    param = 0.35 + random.nextDouble() * 0.75;   // fills the disc under the cloud base
                } else {
                    // Golden-ratio sequences spread puffs evenly over height and angle (no clumps, no bare patches).
                    long n = sequence.merge(t.id(), 1L, Long::sum);
                    h = lowest + (1.0 - lowest) * frac(n * 0.6180339887);
                    angle = frac(n * 0.7548776662 + 0.31) * Math.PI * 2.0;
                    param = 0.93 + random.nextDouble() * 0.14;   // thin sheath: small radial jitter
                }
                double r = radiusAt(v, h, kind, param);
                double[] pos = place(v, h, angle, r, kind);
                mc.particleEngine.add(new FunnelParticle(level, t.id(), pos[0], pos[1], pos[2], (float) h, kind,
                        (float) param, angle, sprites, random));
            }
        }
        spawnRemainder = Math.min(spawnRemainder, 1.0);
    }
}
