package com.tridentxero.xweathermod.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.storm.ClientStormState;
import com.tridentxero.xweathermod.storm.StormCell;
import com.tridentxero.xweathermod.storm.StormSnapshot;
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
 *  - Four kinds of puff: BODY (the condensation funnel, a thin sheath), DUST (a low, wide, brown debris skirt
 *    around the base, only once the funnel touches down), COLLAR (the WALL CLOUD) and TAIL (the tail cloud).
 *  - The wall cloud is a wide, dark, rotating bowl hanging below the main cloud layer, lowest at the middle where
 *    the funnel attaches, with a ragged lighter rim and a few detached scud puffs. The tail cloud is a curved
 *    band of small, lighter puffs that arcs out behind it and drifts in toward the wall cloud.
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
    public static final int KIND_COLLAR = 2; // the wall cloud
    public static final int KIND_TAIL = 3;

    /** How far below the main cloud layer the wall cloud hangs, and how thick the bowl is (blocks). */
    private static final double WALL_DROP = 42.0;
    private static final double WALL_THICKNESS = 30.0;

    private static final double STALE_TICKS = 100.0;
    /** Every funnel / cloud puff lives exactly this long, so the evenly spread spawn order is also the recycling order. */
    public static final int PUFF_LIFE = 110;
    private static final int MAX_SPAWNS_PER_TORNADO_PER_TICK = 320;

    /** Per-tornado state for the particles. Replaced wholesale each tick; reads never block. vx/vz in blocks per tick. */
    public record TornadoView(int id, double cx, double cz, double vx, double vz, double baseY, double topY,
                              double size, double windMph, double intensity, double time) {
    }

    /**
     * Per-supercell state for the wall cloud and tail cloud. The wall cloud belongs to the storm, not the tornado:
     * it lowers and widens as the supercell develops (before any tornado exists) and the funnel hangs from it.
     * cx/cz is the wall cloud's centre (a quarter radius behind the storm centre); R its current radius;
     * topY the height of its lowest point.
     */
    public record WallView(int id, double cx, double cz, double vx, double vz, double R, double topY, double ceilY,
                           double development, double intensity, double time) {
    }

    /** Player position, refreshed every tick, for the visible-arc maths below. */
    private static volatile double playerX = 0.0;
    private static volatile double playerZ = 0.0;
    private static volatile Map<Integer, WallView> walls = Map.of();
    private static final Map<Integer, Double> wallBase = new HashMap<>();
    private static volatile Map<Integer, TornadoView> views = Map.of();
    private static final Map<Integer, Double> smoothedBase = new HashMap<>();
    private static ClientLevel lastLevel = null;
    private static double spawnRemainder = 0.0;
    private static double wallRemainder = 0.0;
    private static double tailRemainder = 0.0;
    /** Per-tornado spawn counter driving the evenly spread (low-discrepancy) puff placement. */
    private static final Map<Integer, Long> sequence = new HashMap<>();
    /** Same idea for the wall cloud (key = id * 2) and the tail cloud (key = id * 2 + 1). */
    private static final Map<Long, Long> wallSequence = new HashMap<>();

    private FunnelSpawner() {
    }

    public static TornadoView view(int tornadoId) {
        return views.get(tornadoId);
    }

    public static WallView wallView(int stormId) {
        return walls.get(stormId);
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

    /**
     * Funnel shape by tornado type (radius as a multiple of size at height fraction h):
     *  - WEDGE (size 60+): very wide, almost a cylinder, flaring only at the top;
     *  - STOVEPIPE (166+ mph, narrower): a tube of nearly constant width;
     *  - otherwise the classic cone / trumpet (thin stalk, flaring into the cloud).
     */
    public static double profile(TornadoView v, double h) {
        if (v.size() >= 60.0) {
            return 0.50 + 0.18 * h + 0.9 * h * h * h * h;
        }
        if (v.windMph() >= 166.0 && v.size() >= 8.0) {
            return 0.30 + 0.08 * h + 1.0 * Math.pow(h, 6.0);
        }
        return profile(h);
    }

    /**
     * While the funnel is still descending (not yet on the ground) its lowest part narrows to a point, like a real
     * funnel cloud reaching down from the wall cloud.
     */
    private static double taper(TornadoView v, double h) {
        double reach = Math.max(0.0, Math.min(1.0, v.intensity() / 0.6));
        if (reach >= 1.0) {
            return 1.0;
        }
        double lowest = 1.0 - reach;
        return 0.15 + 0.85 * smooth((h - lowest) / 0.14);
    }

    /** 0.45 for a barely-there rope, up to 1.0 for a mature tornado. */
    public static double rope(TornadoView v) {
        return 0.45 + 0.55 * smooth(v.intensity() / 0.5);
    }

    /** Orbit radius in blocks. param: radial multiplier for the puff (jitter for BODY, spread for DUST and COLLAR). */
    public static double radiusAt(TornadoView v, double h, int kind, double param) {
        return switch (kind) {
            case KIND_DUST -> v.size() * (0.25 + param);
            default -> v.size() * profile(v, h) * rope(v) * taper(v, h) * param;
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

    /** Funnel puffs created per tick for one tornado (before the dust/body split), capped by the particle budget. */
    public static double bodyRate(TornadoView v, int tornadoCount) {
        double rate = 110.0 * v.intensity() * Math.sqrt(Math.max(4.0, Math.min(400.0, v.size())) / 30.0)
                * ClientConfig.FUNNEL_DENSITY.get();
        int max = ClientConfig.FUNNEL_MAX_PARTICLES.get();
        return Math.min(rate, max * 0.65 / PUFF_LIFE / Math.max(1, tornadoCount));
    }

    /**
     * Average distance between neighbouring funnel puffs: the funnel's surface area divided by the number of puffs
     * alive. Puffs are sized to half this, so neighbours touch instead of overlapping.
     */
    /**
     * For a very wide tornado only part of its funnel is anywhere near you. Returns {centre, half-width} (radians)
     * of the stretch of a ring of radius r around the tornado that is within range of the player; half-width PI means
     * the whole ring. Puffs are only created inside this stretch, so they stay dense where you can see them.
     */
    public static double[] visibleArc(TornadoView v, double r) {
        double range = ClientConfig.FUNNEL_MAX_DISTANCE.get();
        double dx = playerX - v.cx();
        double dz = playerZ - v.cz();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (r + d <= range || r < 1.0) {
            return new double[] {0.0, Math.PI};
        }
        double centre = Math.atan2(dz, dx);
        if (d < 1.0) {
            return new double[] {centre, Math.PI};
        }
        double c = (r * r + d * d - range * range) / (2.0 * r * d);
        double half = Math.acos(Math.max(-1.0, Math.min(1.0, c)));
        return new double[] {centre, Math.max(0.05, half)};
    }

    public static double bodySpacing(TornadoView v) {
        double reach = Math.max(0.0, Math.min(1.0, v.intensity() / 0.6));
        double lowest = 1.0 - reach;
        double height = (v.topY() - v.baseY()) * reach;
        double sum = 0.0;
        int samples = 12;
        for (int i = 0; i < samples; i++) {
            double h = lowest + reach * (i + 0.5) / samples;
            sum += radiusAt(v, h, KIND_BODY, 1.0);
        }
        double meanR = sum / samples;
        double fraction = visibleArc(v, meanR)[1] / Math.PI;
        double area = height * 2.0 * Math.PI * meanR * fraction;
        // Only the outer sheath (60% of body puffs) forms the visible surface; the rest fill the inside.
        double count = Math.max(1.0, bodyRate(v, Math.max(1, views.size())) * PUFF_LIFE * 0.82 * 0.6);
        return Math.sqrt(Math.max(1.0, area) / count);
    }

    /** Half-width of one wall-cloud puff: small, so the bowl looks smooth rather than tiled. */
    public static double wallHalfSize(WallView w) {
        return Math.max(3.0, Math.min(80.0, w.R() * 0.055));
    }

    /** Wall-cloud puffs alive: the bowl's area divided by the area of one puff. */
    public static double wallPuffCount(WallView w) {
        double h = wallHalfSize(w);
        return Math.max(40.0, Math.min(1400.0, Math.PI * w.R() * w.R() * 1.15 / (4.0 * h * h)));
    }

    /**
     * World position of a wall-cloud or tail-cloud puff. f is the puff's radial fraction (0 = centre, 1 = rim; a few
     * detached puffs go past 1), jitter in -0.3..0.3 adds a little height noise.
     *
     * The wall cloud is a lowering OF the cloud base: its rim meets the main cloud layer (w.ceilY) and it slopes
     * down to its lowest point in the middle, with a slightly lower collar right where the funnel attaches. The
     * tail cloud runs FORWARD from it toward the rain area, as a real inflow tail does.
     */
    public static double[] placeWall(WallView w, int kind, double f, double angle, double jitter) {
        double R = w.R();
        if (kind == KIND_TAIL) {
            double speed = Math.sqrt(w.vx() * w.vx() + w.vz() * w.vz());
            double fwd = speed > 1.0E-9 ? Math.atan2(w.vz(), w.vx()) : 0.0;
            double a = fwd - 0.9 * f;                   // the tail curves round to one side
            double dist = R * (0.9 + 1.4 * f);
            double side = jitter * R * 0.12;
            double x = w.cx() + Math.cos(a) * dist - Math.sin(a) * side;
            double z = w.cz() + Math.sin(a) * dist + Math.cos(a) * side;
            double y = w.topY() + (w.ceilY() - w.topY()) * 0.55 * f + jitter * 3.0; // rises as it leaves the wall cloud
            return new double[] {x, y, z};
        }
        double r = R * f;
        double x = w.cx() + Math.cos(angle) * r;
        double z = w.cz() + Math.sin(angle) * r;
        double ff = Math.min(f, 1.1);
        double y = w.topY() + (w.ceilY() - w.topY()) * Math.pow(ff, 1.4) + jitter * 3.0
                - 5.0 * Math.max(0.0, 1.0 - f / 0.22);          // lower collar around the funnel
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
            walls = Map.of();
            smoothedBase.clear();
            wallBase.clear();
            sequence.clear();
            wallSequence.clear();
            FunnelParticle.LIVE.set(0);
            spawnRemainder = 0.0;
            wallRemainder = 0.0;
            tailRemainder = 0.0;
        }
        if (level == null || player == null || mc.isPaused()) {
            return;
        }

        playerX = player.getX();
        playerZ = player.getZ();
        double elapsedTicks = (System.nanoTime() - ClientStormState.lastUpdateNanos()) / 50_000_000.0;
        if (!ClientConfig.FUNNEL_ENABLED.get() || elapsedTicks > STALE_TICKS) {
            views = Map.of();
            walls = Map.of();
            smoothedBase.clear();
            wallBase.clear();
            sequence.clear();
            wallSequence.clear();
            return;
        }
        List<TornadoSnapshot> tornadoes = ClientStormState.tornadoes();

        double maxDist = ClientConfig.FUNNEL_MAX_DISTANCE.get();
        double cloudTop = ClientConfig.ALTITUDE.get() - 6.0;
        // Lowest the wall cloud (and so the top of the funnel) hangs: WALL_DROP below the main cloud layer.
        double lowTop = cloudTop - (WALL_DROP - 6.0);
        double time = level.getGameTime();

        Map<Integer, TornadoView> next = new HashMap<>();
        for (TornadoSnapshot t : tornadoes) {
            double cx = t.x() + t.vx() * elapsedTicks;
            double cz = t.z() + t.vz() * elapsedTicks;
            double dx = cx - player.getX();
            double dz = cz - player.getZ();
            double reachOut = maxDist + Math.min(t.size(), 20000.0) * 1.5; // a very wide tornado can surround you
            if (dx * dx + dz * dz > reachOut * reachOut || t.intensity() < 0.02F) {
                continue;
            }
            // Ground height under the tornado. In unloaded client chunks the heightmap is not meaningful, so fall
            // back to the player's height for far-away tornadoes.
            double target = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(cx), (int) Math.floor(cz));
            if (target <= level.getMinY() + 1) {
                target = player.getY();
            }
            double base = smoothedBase.merge(t.id(), target, (old, now) -> old + (now - old) * 0.15);
            double top = Math.max(lowTop, base + 40.0);
            next.put(t.id(), new TornadoView(t.id(), cx, cz, t.vx(), t.vz(), base, top, t.size(), t.windMph(),
                    t.intensity(), time));
        }
        smoothedBase.keySet().retainAll(next.keySet());
        views = next;

        // Wall clouds: one per developing or mature supercell, whether or not it has a tornado yet.
        Map<Integer, WallView> nextWalls = new HashMap<>();
        for (StormSnapshot s : ClientStormState.storms()) {
            if (!s.supercell() || s.development() < 0.4F || s.intensity() < 0.05F) {
                continue;
            }
            double cx = s.x() + s.vx() * elapsedTicks;
            double cz = s.z() + s.vz() * elapsedTicks;
            double sp = Math.sqrt((double) s.vx() * s.vx() + (double) s.vz() * s.vz());
            double fx = sp > 1.0E-9 ? s.vx() / sp : 1.0;
            double fz = sp > 1.0E-9 ? s.vz() / sp : 0.0;
            double wx = cx - fx * s.radius() * StormCell.TORNADO_BACK_FRACTION;
            double wz = cz - fz * s.radius() * StormCell.TORNADO_BACK_FRACTION;
            double dx = wx - player.getX();
            double dz = wz - player.getZ();
            double wallReach = maxDist + s.radius() * 0.5;
            if (dx * dx + dz * dz > wallReach * wallReach) {
                continue;
            }
            double target = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(wx), (int) Math.floor(wz));
            if (target <= level.getMinY() + 1) {
                target = player.getY();
            }
            double base = wallBase.merge(s.id(), target, (old, now) -> old + (now - old) * 0.15);
            double lowest = Math.max(lowTop, base + 40.0);
            // 0 -> 1 over the second half of the build: the wall cloud lowers from the cloud base and widens.
            double grow = smooth((s.development() - 0.4) / 0.5);
            double top = cloudTop + (lowest - cloudTop) * grow;
            double R = Math.max(40.0, Math.min(3000.0, s.radius() * 0.22)) * (0.5 + 0.5 * grow);
            nextWalls.put(s.id(), new WallView(s.id(), wx, wz, s.vx(), s.vz(), R, top, cloudTop, s.development(),
                    s.intensity(), time));
        }
        wallBase.keySet().retainAll(nextWalls.keySet());
        walls = nextWalls;

        if (VolumetricClouds.active()) {
            // The volumetric renderer draws the funnel and wall cloud from the views above, so no puffs.
            return;
        }

        SpriteSet sprites = CloudParticleProvider.SOFT_SPRITES;
        int maxParticles = ClientConfig.FUNNEL_MAX_PARTICLES.get();
        if (sprites == null || maxParticles <= 0) {
            return;
        }
        RandomSource random = level.getRandom();
        double density = ClientConfig.FUNNEL_DENSITY.get();

        // Wall cloud and tail cloud first, so a full funnel budget can never starve them. Puffs are spread evenly
        // (golden-ratio sunflower pattern) and each lives exactly PUFF_LIFE ticks, so the pattern stays even while
        // puffs are replaced one by one.
        for (WallView w : nextWalls.values()) {
            double wallN = wallPuffCount(w);
            double gate = Math.min(1.0, w.intensity() * 2.5);
            wallRemainder += wallN / PUFF_LIFE * gate * density;
            tailRemainder += wallN * 0.35 / PUFF_LIFE * gate * density;
            int walls = (int) wallRemainder;
            int tails = (int) tailRemainder;
            wallRemainder -= walls;
            tailRemainder -= tails;
            for (int i = 0; i < walls + tails; i++) {
                if (FunnelParticle.LIVE.get() >= maxParticles) {
                    break;
                }
                boolean tail = i >= walls;
                int kind = tail ? KIND_TAIL : KIND_COLLAR;
                long n = wallSequence.merge(w.id() * 2L + (tail ? 1 : 0), 1L, Long::sum);
                double u = frac(n * 0.6180339887);
                double angle = frac(n * 0.7548776662 + 0.31) * Math.PI * 2.0;
                double f;
                if (tail) {
                    f = u;
                } else if (n % 10 == 0) {
                    f = 1.03 + u * 0.2;                          // a few detached scud puffs beyond the rim
                } else {
                    f = Math.sqrt(u) * 0.98;                     // even over the disc's area
                }
                // Three stacked layers give the wall cloud thickness: lowest layer darkest, top layer brightest.
                double jitter = ((int) (n % 3) - 1) * 1.6;
                double[] pos = placeWall(w, kind, f, angle, jitter);
                double pdx = pos[0] - player.getX();
                double pdz = pos[2] - player.getZ();
                if (pdx * pdx + pdz * pdz > maxDist * maxDist) {
                    continue; // out of sight range: do not spend budget on it
                }
                // The wall cloud mixes in your banded cloud texture for streaky structure.
                SpriteSet puffSprites = sprites;
                if (!tail && CloudParticleProvider.BANDED_SPRITES != null && ((int) (f * 6.0)) % 2 == 1) {   // concentric streaks
                    puffSprites = CloudParticleProvider.BANDED_SPRITES;
                }
                mc.particleEngine.add(new FunnelParticle(level, w.id(), pos[0], pos[1], pos[2], 1.0F, kind,
                        (float) f, (float) jitter, angle, puffSprites, random));
            }
        }
        wallRemainder = Math.min(wallRemainder, 1.0);
        tailRemainder = Math.min(tailRemainder, 1.0);

        for (TornadoSnapshot t : tornadoes) {
            TornadoView v = next.get(t.id());
            if (v == null) {
                continue;
            }
            // Capped by the budget so the spawn rate stays steady (no gaps).
            double rate = bodyRate(v, next.size());
            spawnRemainder += rate;
            int count = Math.min(MAX_SPAWNS_PER_TORNADO_PER_TICK, (int) spawnRemainder);
            spawnRemainder -= count;

            // How far down the funnel has descended: 0 at no intensity, reaches the ground at 0.6.
            double reach = Math.max(0.0, Math.min(1.0, v.intensity() / 0.6));
            double lowest = 1.0 - reach;

            for (int i = 0; i < count; i++) {
                if (FunnelParticle.LIVE.get() >= maxParticles) {
                    break;
                }
                int kind = (reach >= 1.0 && random.nextFloat() < 0.18F) ? KIND_DUST : KIND_BODY;

                double h;
                double angle;
                double param;
                if (kind == KIND_DUST) {
                    h = random.nextDouble() * 0.09;              // a low skirt, not a column
                    angle = random.nextDouble() * Math.PI * 2.0;
                    param = 0.05 + random.nextDouble() * 1.75;   // out to about 2 x size
                } else {
                    // Golden-ratio sequences spread puffs evenly over height and angle (no clumps, no bare patches).
                    long n = sequence.merge(t.id(), 1L, Long::sum);
                    h = lowest + (1.0 - lowest) * frac(n * 0.6180339887);
                    double[] arc = visibleArc(v, radiusAt(v, 0.5, KIND_BODY, 1.0));
                    angle = arc[0] + (frac(n * 0.7548776662 + 0.31) * 2.0 - 1.0) * arc[1];
                    // 60% form the outer sheath; 40% fill the inside (down to 35% of the radius, denser toward the
                    // edge) so the funnel is a solid column with a darker core, not a hollow shell.
                    param = random.nextFloat() < 0.6F
                            ? 0.93 + random.nextDouble() * 0.14
                            : 0.35 + 0.58 * Math.sqrt(random.nextDouble());
                }
                double r = radiusAt(v, h, kind, param);
                double[] pos = place(v, h, angle, r, kind);
                mc.particleEngine.add(new FunnelParticle(level, t.id(), pos[0], pos[1], pos[2], (float) h, kind,
                        (float) param, 0.0F, angle, sprites, random));
            }
        }
        spawnRemainder = Math.min(spawnRemainder, 1.0);
    }
}
