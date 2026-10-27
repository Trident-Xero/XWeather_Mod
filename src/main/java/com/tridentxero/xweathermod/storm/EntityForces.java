package com.tridentxero.xweathermod.storm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.tridentxero.xweathermod.Config;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server-authoritative wind and tornado forces on entities. Motion only: this class never damages anything
 * directly and never touches blocks. (Being thrown and landing can still hurt through normal fall damage.)
 *
 * What it does, once per server tick:
 *  - Wind: entities that can see the sky are pushed toward the wind from {@link WindField}. Outside tornadoes the
 *    push is gentle and only above {@link #STORM_PUSH_MIN_MPH}.
 *  - Tornadoes: within 4 x size (max 160 blocks) of a tornado, entities are pulled around and inward by the
 *    tornado's wind. Tornadoes with at least {@link #LIFT_MIN_MPH} effective wind also lift entities inside
 *    1.6 x size of the centre.
 *  - Heavier (bigger) entities are affected less. Items and small mobs are affected most.
 *  - Skipped: spectators, creative-mode players, passengers, armour stands, anything that cannot see the sky, and
 *    players when forces.affectPlayers is off. Never loads chunks: only entities that already exist are touched.
 *
 * Gameplay model, not physics. All strengths scale with forces.strength.
 *
 * UNVERIFIED against a compile on 26.1.2: ServerLevel#getEntities(Entity, AABB, Predicate), Entity#setDeltaMovement,
 * Entity#hurtMarked, ServerLevel#canSeeSky(BlockPos), Entity#blockPosition, Entity#isPassenger, Entity#isSpectator,
 * Player#isCreative, and the packages of ArmorStand and ItemEntity.
 */
public final class EntityForces {
    private static final double BLOCKS_PER_TICK_PER_MPH = 0.0224;
    private static final double STORM_PUSH_MIN_MPH = 40.0;
    private static final double LIFT_MIN_MPH = 100.0;
    private static final double MAX_HORIZONTAL_SPEED = 1.4;
    private static final double MAX_UP_SPEED = 0.9;
    private static final double PLAYER_RANGE = 48.0;
    private static final double Y_MIN = -128.0;
    private static final double Y_MAX = 512.0;

    private EntityForces() {
    }

    public static void tick(ServerLevel level, StormSavedData data) {
        if (!Config.FORCES_ENABLED.get() || level.players().isEmpty()) {
            return;
        }
        if (data.storms().isEmpty() && data.tornadoes().isEmpty()) {
            return;
        }
        if (data.lastTick() < 0) {
            return; // simulation has not taken its first step yet
        }
        double elapsed = Math.max(0L, level.getGameTime() - data.lastTick());
        List<StormSnapshot> storms = new ArrayList<>(data.storms().size());
        for (StormCell c : data.storms()) {
            storms.add(c.snapshot());
        }
        List<TornadoSnapshot> tornadoes = new ArrayList<>(data.tornadoes().size());
        for (TornadoCell c : data.tornadoes()) {
            tornadoes.add(c.snapshot());
        }

        // --- Collect candidate entities (each once) ---
        int cap = Config.FORCES_MAX_ENTITIES.get();
        Set<Integer> seen = new HashSet<>();
        List<Entity> candidates = new ArrayList<>();
        for (TornadoSnapshot t : tornadoes) {
            double cx = t.x() + t.vx() * elapsed;
            double cz = t.z() + t.vz() * elapsed;
            double reach = Math.min(160.0, Math.max(16.0, t.size() * 4.0));
            collect(level, new AABB(cx - reach, Y_MIN, cz - reach, cx + reach, Y_MAX, cz + reach), seen, candidates, cap);
        }
        if (Config.FORCES_STORM_WIND.get() && !storms.isEmpty()) {
            for (ServerPlayer p : level.players()) {
                collect(level, new AABB(p.getX() - PLAYER_RANGE, Y_MIN, p.getZ() - PLAYER_RANGE,
                        p.getX() + PLAYER_RANGE, Y_MAX, p.getZ() + PLAYER_RANGE), seen, candidates, cap);
            }
        }

        double strength = Config.FORCES_STRENGTH.get();
        double gameTime = level.getGameTime();
        for (Entity entity : candidates) {
            apply(level, entity, storms, tornadoes, elapsed, gameTime, strength);
        }
    }

    private static void collect(ServerLevel level, AABB box, Set<Integer> seen, List<Entity> out, int cap) {
        for (Entity e : level.getEntities((Entity) null, box, EntityForces::eligible)) {
            if (out.size() >= cap) {
                return;
            }
            if (seen.add(e.getId())) {
                out.add(e);
            }
        }
    }

    private static boolean eligible(Entity e) {
        if (e.isRemoved() || e.isPassenger() || e.isSpectator()) {
            return false;
        }
        if (e instanceof ServerPlayer p) {
            return Config.FORCES_AFFECT_PLAYERS.get() && !p.isCreative();
        }
        return (e instanceof LivingEntity && !(e instanceof ArmorStand)) || e instanceof ItemEntity;
    }

    private static void apply(ServerLevel level, Entity entity, List<StormSnapshot> storms,
                              List<TornadoSnapshot> tornadoes, double elapsed, double gameTime, double strength) {
        if (!level.canSeeSky(entity.blockPosition())) {
            return; // sheltered
        }
        double ex = entity.getX();
        double ez = entity.getZ();

        // Nearest tornado influence (zone 0..1 inside 1.6 x size) and the effective wind of that tornado.
        double zone = 0.0;
        double zoneWind = 0.0;
        boolean inTornadoReach = false;
        for (TornadoSnapshot t : tornadoes) {
            double dx = ex - (t.x() + t.vx() * elapsed);
            double dz = ez - (t.z() + t.vz() * elapsed);
            double d = Math.sqrt(dx * dx + dz * dz);
            double reach = Math.min(160.0, Math.max(16.0, t.size() * 4.0));
            if (d <= reach) {
                inTornadoReach = true;
            }
            double core = t.size() * 1.6;
            if (d < core) {
                double z = 1.0 - d / core;
                double w = t.windMph() * t.intensity();
                if (z * w > zone * zoneWind) {
                    zone = z;
                    zoneWind = w;
                }
            }
        }

        WindField.Wind wind = WindField.sample(storms, tornadoes, elapsed, gameTime, ex, ez);
        double mph = wind.gustSpeedMph();
        if (!inTornadoReach && mph < STORM_PUSH_MIN_MPH) {
            return;
        }
        if (mph < 1.0E-3) {
            return;
        }

        double mass = Math.sqrt(Math.max(0.25, Math.min(4.0,
                entity.getBbWidth() * entity.getBbWidth() * entity.getBbHeight())));
        double playerFactor = entity instanceof ServerPlayer ? 0.6 : 1.0;

        double dirX = wind.x() / wind.speedMph();
        double dirZ = wind.z() / wind.speedMph();
        double speed = mph * BLOCKS_PER_TICK_PER_MPH;

        Vec3 v = entity.getDeltaMovement();
        double vx = v.x;
        double vy = v.y;
        double vz = v.z;

        double coupling;
        double targetScale;
        if (inTornadoReach) {
            coupling = 0.10 * strength * playerFactor / mass;
            targetScale = 0.9;
        } else {
            coupling = 0.05 * strength * playerFactor / mass;
            targetScale = 0.25 / mass;
        }
        coupling = Math.min(0.5, coupling);
        double tx = dirX * speed * targetScale;
        double tz = dirZ * speed * targetScale;
        vx += (tx - vx) * coupling;
        vz += (tz - vz) * coupling;

        if (zone > 0.0 && zoneWind >= LIFT_MIN_MPH) {
            double lift = 0.14 * (zoneWind / 130.0) * zone * strength * playerFactor / mass;
            vy = Math.min(MAX_UP_SPEED, vy + lift);
        }

        double h = Math.sqrt(vx * vx + vz * vz);
        if (h > MAX_HORIZONTAL_SPEED) {
            vx *= MAX_HORIZONTAL_SPEED / h;
            vz *= MAX_HORIZONTAL_SPEED / h;
        }
        entity.setDeltaMovement(new Vec3(vx, vy, vz));
        entity.hurtMarked = true; // make the server send the new velocity to clients
    }
}
