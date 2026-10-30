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
    private static final int MAX_SPAWNS_PER_STORM_PER_TICK = 14;
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

        if (VolumetricClouds.active()) {
            // The volumetric renderer draws the storm clouds; let any particle puffs left over fade out.
            CloudParticle.FADE_ALL = true;
            return;
        }

        tickCounter++;
        double elapsedTicks = (System.nanoTime() - ClientStormState.lastUpdateNanos()) / 50_000_000.0;
        if (ClientConfig.DEBUG_LOGGING.get() && tickCounter % DEBUG_LOG_INTERVAL_TICKS == 0) {
            logDebug(player, elapsedTicks);
        }

        CloudParticle.FADE_ALL = ClientStormState.storms().isEmpty() || elapsedTicks > STALE_TICKS;

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

            double rate = storm.intensity() * (storm.radius() / 100.0) * PUFFS_PER_TICK_PER_100_BLOCKS * density
                    * (storm.supercell() ? 3.5 : 1.0);
            spawnRemainder += rate;
            int count = Math.min(MAX_SPAWNS_PER_STORM_PER_TICK, (int) spawnRemainder);
            spawnRemainder -= count;

            for (int i = 0; i < count; i++) {
                if (CloudParticle.LIVE.get() >= maxPuffs) {
                    return;
                }
                if (storm.supercell()) {
                    // A big storm is mostly out of range: try a few times so the near part still gets puffs.
                    for (int attempt = 0; attempt < 4; attempt++) {
                        if (spawnSupercellPuff(mc, level, storm, cx, cz, altitude, player.getX(), player.getZ(),
                                maxDistance, random)) {
                            break;
                        }
                    }
                    continue;
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
                double hv = random.nextDouble();                    // position inside the cloud layer, 0 = underside
                double py = altitude - 8.0 + hv * 34.0;            // a 34-block-deep layer instead of a single sheet

                // Dark, banded core for strong storms; soft light puffs around the edges.
                boolean core = (r / storm.radius()) < 0.55 && storm.intensity() > 0.35F;
                double darkness = storm.intensity() * (core ? 1.0 : 0.6);
                spawnPuff(mc, level, core, px, py, pz, storm.vx(), darkness, storm.vz(), random, (float) hv);
            }
        }
        // Keep the carry-over from growing without bound when the budget is full.
        spawnRemainder = Math.min(spawnRemainder, 1.0);
    }

    /**
     * One puff of a supercell. A supercell is built from the parts a real one has, each placed relative to the
     * storm's moving centre and its direction of travel (forward = the way it moves; "right" = to the right of that):
     *
     *  - BASE: the flat, dark cloud base. It starts as a small cell and grows to the full storm. It is lowest and
     *    darkest in the forward flank (the rain area ahead of the updraft), and has a CLEAR SLOT (the rear-flank
     *    downdraft) cut out behind and to the right of the wall cloud once the storm is developed.
     *  - UPDRAFT TOWER: a lumpy column over the centre that grows from a small cumulus to a tall tower, bulging in
     *    the middle and leaning downwind with height. Dark at the bottom, white at the top, with banded (striated)
     *    puffs on its flanks.
     *  - OVERSHOOTING TOP: a bright dome that pokes up through the top of the tower when the storm is vigorous.
     *  - ANVIL: a thin, pale shield spread at the top of the tower, mostly downwind with a short overhang upwind,
     *    wider than the storm itself.
     *  - MAMMATUS: small pouches hanging from the underside of the outer anvil.
     *  - SHELF: a low, dark, banded arc along the leading edge (the gust front / shelf cloud).
     * Base and tower puffs also drift slowly around the wall-cloud position (anticlockwise from above), so the
     * storm appears to rotate. The wall cloud and tail cloud are made by {@link FunnelSpawner}.
     *
     * Formation is driven by development d (0..1, from the server): cumulus tower first, then base growth and
     * darkening, then anvil spreading, then the shelf, the clear slot and the lowering wall cloud.
     * Returns false if the puff was dropped (out of range, in the clear slot, not yet formed, no textures yet).
     */
    private static boolean spawnSupercellPuff(Minecraft mc, ClientLevel level, StormSnapshot storm, double cx, double cz,
                                              double altitude, double playerX, double playerZ, double maxDistance,
                                              RandomSource random) {
        double R = storm.radius();
        double d = storm.development();
        double I = storm.intensity();
        double sp = Math.sqrt((double) storm.vx() * storm.vx() + (double) storm.vz() * storm.vz());
        double fx = sp > 1.0E-9 ? storm.vx() / sp : 1.0;   // forward / downwind
        double fz = sp > 1.0E-9 ? storm.vz() / sp : 0.0;
        double sx = -fz;                                    // to the right of forward (x east, z south)
        double sz = fx;
        double wx = cx - fx * R * 0.25;                     // wall cloud and tornado
        double wz = cz - fz * R * 0.25;

        double tall = 12.0 + 78.0 * smooth01((d - 0.03) / 0.75);      // updraft tower height
        double anvilGrow = smooth01((d - 0.28) / 0.5);
        double slotGrow = smooth01((d - 0.5) / 0.4);
        double sizeMul = Math.max(1.0, Math.min(6.0, Math.pow(R / 350.0, 0.6)));

        // Pick a part from those that exist at this stage of formation.
        double wBase = 0.26;
        double wTower = 0.26;
        double wTop = d > 0.4 ? 0.04 : 0.0;
        double wAnvil = anvilGrow > 0.02 ? 0.22 : 0.0;
        double wMam = d > 0.55 ? 0.08 : 0.0;
        double wShelf = d > 0.45 ? 0.10 : 0.0;
        double total = wBase + wTower + wTop + wAnvil + wMam + wShelf;
        double pick = random.nextDouble() * total;

        double px;
        double pz;
        double py;
        float size;
        float light;
        float alpha;
        boolean banded = false;
        boolean swirl = false;
        double t0;
        float depth = -1.0F;   // 0..1 position inside a thick layer (-1 = already shaded by the part itself)
        if ((pick -= wBase) < 0.0) {                                  // BASE
            double rb = R * (0.22 + 0.78 * smooth01(d / 0.55));
            double r = rb * Math.pow(random.nextDouble(), 0.7);
            double a = random.nextDouble() * Math.PI * 2.0;
            px = cx + Math.cos(a) * r;
            pz = cz + Math.sin(a) * r;
            // Clear slot: no base cloud in a circle behind and to the right of the wall cloud.
            double nx = wx - fx * R * 0.10 + sx * R * 0.20;
            double nz = wz - fz * R * 0.10 + sz * R * 0.20;
            double slotR = R * 0.20 * slotGrow;
            if (slotGrow > 0.02 && (px - nx) * (px - nx) + (pz - nz) * (pz - nz) < slotR * slotR) {
                return false;
            }
            // Forward flank (rain area): lower and darker, centred ahead of the updraft.
            double ffx = px - (cx + fx * R * 0.30);
            double ffz = pz - (cz + fz * R * 0.30);
            double ff = Math.max(0.0, 1.0 - Math.sqrt(ffx * ffx + ffz * ffz) / (R * 0.55));
            depth = (float) random.nextDouble();
            py = altitude - 4.0 + depth * 20.0 - 5.0 * (1.0 - r / rb) - 9.0 * ff;   // a 20-block-deep base
            size = 1.0F;
            light = (float) (0.52 - 0.26 * I * (1.0 - 0.5 * r / rb) - 0.12 * ff * I);
            alpha = 1.15F;
            banded = r < rb * 0.6;
            swirl = true;
        } else if ((pick -= wTower) < 0.0) {                          // UPDRAFT TOWER
            double hFrac = Math.pow(random.nextDouble(), 0.8);
            double bulge = 0.10 + 0.18 * Math.pow(Math.sin(Math.PI * Math.min(1.0, hFrac * 0.9 + 0.1)), 0.7);
            double r = R * bulge * Math.sqrt(random.nextDouble());
            double a = random.nextDouble() * Math.PI * 2.0;
            double lean = hFrac * R * 0.14 * d;                       // leans downwind with height
            px = cx + fx * (R * 0.08 + lean) + Math.cos(a) * r;
            pz = cz + fz * (R * 0.08 + lean) + Math.sin(a) * r;
            py = altitude + 4.0 + hFrac * tall;
            size = 0.55F + 0.25F * (float) (1.0 - hFrac);             // lumpy: small puffs
            light = 0.40F + 0.55F * (float) hFrac;                    // dark base, bright top
            alpha = 1.1F;
            banded = random.nextFloat() < 0.35F && r > R * bulge * 0.6; // striations on the flanks
            swirl = true;
        } else if ((pick -= wTop) < 0.0) {                            // OVERSHOOTING TOP
            double r = R * 0.07 * Math.sqrt(random.nextDouble());
            double a = random.nextDouble() * Math.PI * 2.0;
            double lean = R * 0.14 * d;
            px = cx + fx * (R * 0.08 + lean) + Math.cos(a) * r;
            pz = cz + fz * (R * 0.08 + lean) + Math.sin(a) * r;
            py = altitude + 4.0 + tall + random.nextDouble() * 12.0 * smooth01((d - 0.4) / 0.4);
            size = 0.5F;
            light = 0.97F;
            alpha = 1.1F;
        } else if ((pick -= wAnvil) < 0.0) {                          // ANVIL
            t0 = random.nextDouble();
            double along = (-0.35 + 1.95 * t0) * R * anvilGrow;       // short overhang upwind, long spread downwind
            double half = R * anvilGrow * (0.30 + 0.65 * Math.pow(Math.sin(Math.PI * Math.min(1.0, t0 * 1.1)), 0.8));
            double across = (random.nextDouble() * 2.0 - 1.0) * half;
            px = cx + fx * along + sx * across;
            pz = cz + fz * along + sz * across;
            depth = (float) random.nextDouble();
            py = altitude + 4.0 + tall * 0.95 - 3.0 + depth * 9.0;   // anvil is a slab about 9 blocks thick
            size = 1.25F;
            light = 0.84F + 0.14F * random.nextFloat();
            alpha = 0.55F;
        } else if ((pick -= wMam) < 0.0) {                            // MAMMATUS
            t0 = 0.35 + 0.65 * random.nextDouble();                   // outer part of the anvil only
            double along = (-0.35 + 1.95 * t0) * R * anvilGrow;
            double half = R * anvilGrow * (0.30 + 0.65 * Math.pow(Math.sin(Math.PI * Math.min(1.0, t0 * 1.1)), 0.8));
            double across = (random.nextDouble() * 2.0 - 1.0) * half;
            px = cx + fx * along + sx * across;
            pz = cz + fz * along + sz * across;
            py = altitude + 4.0 + tall * 0.95 - 5.0 - random.nextDouble() * 8.0;
            size = 0.35F;
            light = 0.66F;
            alpha = 0.75F;
        } else {                                                      // SHELF (gust front)
            double phi = (random.nextDouble() * 2.0 - 1.0) * Math.toRadians(70.0);
            double rr = R * (0.85 + 0.10 * random.nextDouble());
            px = cx + (Math.cos(phi) * fx + Math.sin(phi) * sx) * rr;
            pz = cz + (Math.cos(phi) * fz + Math.sin(phi) * sz) * rr;
            depth = (float) random.nextDouble();
            py = altitude - 11.0 + depth * 8.0;
            size = 1.25F;
            light = (float) (0.46 - 0.12 * I);
            alpha = 1.0F * (float) smooth01((d - 0.45) / 0.3) + 0.1F;
            banded = true;
        }
        double ddx = px - playerX;
        double ddz = pz - playerZ;
        if (ddx * ddx + ddz * ddz > maxDistance * maxDistance) {
            return false;
        }
        SpriteSet sprites = banded ? CloudParticleProvider.BANDED_SPRITES : CloudParticleProvider.SOFT_SPRITES;
        if (sprites == null) {
            return false;
        }
        double vx = storm.vx();
        double vz = storm.vz();
        if (swirl) {
            // Slow drift around the wall cloud, anticlockwise from above (east of centre moves north = -z).
            double rx = px - wx;
            double rz = pz - wz;
            double rl = Math.max(1.0, Math.sqrt(rx * rx + rz * rz));
            double turn = 0.06 * Math.max(0.0, 1.0 - rl / (R * 0.9)) * (0.3 + 0.7 * d);
            vx += rz / rl * turn;
            vz += -rx / rl * turn;
        }
        CloudParticle puff = new CloudParticle(level, px, py, pz, vx, 0.5, vz, sprites, random);
        puff.style(size * (float) sizeMul, Math.max(0.0F, Math.min(1.0F, light)), alpha);
        if (depth >= 0.0F) {
            puff.shade(depth);
        }
        mc.particleEngine.add(puff);
        return true;
    }

    private static double smooth01(double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return c * c * (3.0 - 2.0 * c);
    }

    private static void spawnPuff(Minecraft mc, ClientLevel level, boolean banded,
                                  double x, double y, double z, double xd, double darkness, double zd,
                                  RandomSource random, float height01) {
        SpriteSet sprites = banded ? CloudParticleProvider.BANDED_SPRITES : CloudParticleProvider.SOFT_SPRITES;
        if (sprites == null) {
            return; // providers not built yet
        }
        CloudParticle puff = new CloudParticle(level, x, y, z, xd, darkness, zd, sprites, random);
        puff.shade(height01);
        mc.particleEngine.add(puff);
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
