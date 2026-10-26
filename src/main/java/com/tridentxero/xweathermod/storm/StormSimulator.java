package com.tridentxero.xweathermod.storm;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.tridentxero.xweathermod.Config;
import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.net.StormSyncPayload;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative storm and tornado simulation.
 *
 * Unloaded-region approximation (documented behaviour): storms and tornadoes are plain data, they never load
 * chunks. Each step advances every one by the real game-time elapsed since the previous step, capped by
 * persistence.maxCatchUpMinutes. So they keep drifting and ageing while nobody is there, and a server restart
 * advances them by the downtime (up to the cap). It never needs force-loaded chunks.
 */
public final class StormSimulator {
    private StormSimulator() {
    }

    public static void tickLevel(ServerLevel level, int serverTickCount) {
        if (!isDimensionEnabled(level)) {
            return;
        }
        try {
            if (serverTickCount % Config.SIM_INTERVAL_TICKS.get() == 0) {
                step(level);
            }
            if (serverTickCount % Config.SYNC_INTERVAL_TICKS.get() == 0) {
                sync(level);
            }
        } catch (RuntimeException e) {
            XWeatherMod.LOGGER.error("Storm simulation error (storms may pause this tick)", e);
        }
    }

    public static boolean isDimensionEnabled(ServerLevel level) {
        if (level.dimension() == Level.OVERWORLD) {
            return Config.OVERWORLD_ENABLED.get();
        }
        if (level.dimension() == Level.NETHER) {
            return Config.NETHER_ENABLED.get();
        }
        if (level.dimension() == Level.END) {
            return Config.END_ENABLED.get();
        }
        return false; // unknown/custom dimensions stay off until a proper dimension list exists (later stage)
    }

    private static void step(ServerLevel level) {
        StormSavedData data = level.getDataStorage().computeIfAbsent(StormSavedData.TYPE);
        long now = level.getGameTime();
        if (data.lastTick() < 0) {
            data.setLastTick(now);
            data.setDirty();
            return;
        }
        long maxCatchUp = Config.MAX_CATCH_UP_MINUTES.get() * 1200L;
        long dt = Math.min(Math.max(0L, now - data.lastTick()), maxCatchUp);
        data.setLastTick(now);

        if (dt > 0) {
            Iterator<StormCell> it = data.storms().iterator();
            while (it.hasNext()) {
                StormCell cell = it.next();
                cell.x += cell.vx * dt;
                cell.z += cell.vz * dt;
                cell.age += (int) dt;
                if (cell.expired()) {
                    XWeatherMod.LOGGER.info("Storm {} dissipated at ({}, {})", cell.id, (int) cell.x, (int) cell.z);
                    it.remove();
                }
            }
            Iterator<TornadoCell> tit = data.tornadoes().iterator();
            while (tit.hasNext()) {
                TornadoCell t = tit.next();
                t.x += t.vx * dt;
                t.z += t.vz * dt;
                t.age += (int) dt;
                if (t.expired()) {
                    XWeatherMod.LOGGER.info("Tornado {} ({}) dissipated at ({}, {})",
                            t.id, t.tier().label, (int) t.x, (int) t.z);
                    tit.remove();
                }
            }
            // Supercells that are due drop their tornado. Done after the tornado update so a new tornado
            // is not aged by this same step.
            for (StormCell cell : data.storms()) {
                if (cell.supercell && cell.tornadoAtAge >= 0 && cell.age >= cell.tornadoAtAge) {
                    cell.tornadoAtAge = -1;
                    spawnSupercellTornado(data, cell, false);
                }
            }
            LightningSimulator.tick(level, data, dt);
        }

        List<ServerPlayer> players = level.players();
        if (!players.isEmpty() && data.storms().size() < Config.MAX_STORMS.get()) {
            RandomSource random = level.getRandom();
            boolean spawn;
            if (Config.TEST_MODE.get()) {
                spawn = data.storms().isEmpty();
            } else {
                double chance = Config.SPAWN_CHANCE_PER_MINUTE.get() * Math.max(1L, dt) / 1200.0;
                spawn = random.nextDouble() < chance;
            }
            if (spawn) {
                spawnNearPlayer(data, players, random);
            }
        }
        data.setDirty();
    }

    /**
     * Spawns a tornado at the given position (used by /xweather spawn tornado).
     * Returns null if the per-dimension cap is reached.
     */
    public static TornadoCell spawnTornado(ServerLevel level, double x, double z, double windMph, double size) {
        StormSavedData data = level.getDataStorage().computeIfAbsent(StormSavedData.TYPE);
        if (data.tornadoes().size() >= Config.MAX_TORNADOES.get()) {
            return null;
        }
        RandomSource random = level.getRandom();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double speed = Config.TORNADO_SPEED_BLOCKS_PER_SECOND.get() / 20.0; // blocks per tick
        float vx = (float) (Math.cos(angle) * speed);
        float vz = (float) (Math.sin(angle) * speed);
        int maxAge = Config.TORNADO_DURATION_MINUTES.get() * 1200;

        TornadoCell cell = new TornadoCell(data.allocateId(), x, z, vx, vz, (float) windMph, (float) size, 0, maxAge);
        data.tornadoes().add(cell);
        data.setDirty();
        XWeatherMod.LOGGER.info("Tornado {} ({}, {} mph, radius {}) spawned at ({}, {})",
                cell.id, cell.tier().label, (int) windMph, (int) size, (int) x, (int) z);
        return cell;
    }

    /**
     * Spawns a supercell at the given position (used by /xweather spawn supercell ...).
     * It moves in a random direction at the normal storm speed.
     *
     * @param instant true: starts at full strength and drops its tornado immediately.
     *                false: starts from nothing, ramps up, and drops a tornado at about 40% of its life.
     * @return the storm, or null if the per-dimension storm cap is reached.
     *         Check {@link StormCell#tornadoAtAge} / the tornado list to see whether an instant tornado was created.
     */
    public static StormCell spawnSupercell(ServerLevel level, double x, double z, boolean instant) {
        StormSavedData data = level.getDataStorage().computeIfAbsent(StormSavedData.TYPE);
        if (data.storms().size() >= Config.MAX_STORMS.get()) {
            return null;
        }
        RandomSource random = level.getRandom();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double speed = Config.SPEED_BLOCKS_PER_SECOND.get() / 20.0;
        float vx = (float) (Math.cos(angle) * speed);
        float vz = (float) (Math.sin(angle) * speed);
        int maxAge = Config.SUPERCELL_DURATION_MINUTES.get() * 1200;
        // Intensity reaches full at 15% of life, so an instant supercell starts there.
        int startAge = instant ? (int) (maxAge * 0.15) : 0;
        int tornadoAt = instant ? -1 : (int) (maxAge * 0.40);

        StormCell cell = new StormCell(data.allocateId(), x, z, vx, vz, Config.SUPERCELL_RADIUS.get(), startAge, maxAge,
                true, tornadoAt, Config.SUPERCELL_TORNADO_WIND_MPH.get(), Config.SUPERCELL_TORNADO_SIZE.get());
        data.storms().add(cell);
        if (instant) {
            spawnSupercellTornado(data, cell, true);
        }
        data.setDirty();
        XWeatherMod.LOGGER.info("Supercell {} spawned at ({}, {}), radius {}, {}", cell.id, (int) x, (int) z,
                (int) cell.radius, instant ? "instant" : "building to tornado");
        return cell;
    }

    /** Creates the tornado of a supercell, just behind its centre, travelling with the storm. */
    private static void spawnSupercellTornado(StormSavedData data, StormCell storm, boolean instant) {
        if (data.tornadoes().size() >= Config.MAX_TORNADOES.get()) {
            XWeatherMod.LOGGER.info("Supercell {} could not drop a tornado: tornado limit reached", storm.id);
            return;
        }
        double speed = Math.sqrt(storm.vx * storm.vx + storm.vz * storm.vz);
        double bx = speed > 1.0E-9 ? -storm.vx / speed : 0.0;
        double bz = speed > 1.0E-9 ? -storm.vz / speed : 0.0;
        double x = storm.x + bx * storm.radius * 0.25;
        double z = storm.z + bz * storm.radius * 0.25;

        int stormRemaining = Math.max(1200, storm.maxAge - storm.age);
        int maxAge = Math.min(Config.TORNADO_DURATION_MINUTES.get() * 1200, stormRemaining);
        // Tornado intensity reaches full at 10% of its life, so an instant one starts there.
        int startAge = instant ? (int) (maxAge * 0.10) : 0;
        TornadoCell t = new TornadoCell(data.allocateId(), x, z, storm.vx, storm.vz,
                storm.tornadoWind, storm.tornadoSize, startAge, maxAge);
        data.tornadoes().add(t);
        XWeatherMod.LOGGER.info("Supercell {} produced tornado {} ({}, {} mph)", storm.id, t.id, t.tier().label, (int) t.windMph);
    }

    private static void spawnNearPlayer(StormSavedData data, List<ServerPlayer> players, RandomSource random) {
        ServerPlayer target = players.get(random.nextInt(players.size()));

        double minDist = Math.min(Config.SPAWN_DISTANCE_MIN.get(), Config.SPAWN_DISTANCE_MAX.get());
        double maxDist = Math.max(Config.SPAWN_DISTANCE_MIN.get(), Config.SPAWN_DISTANCE_MAX.get());
        double angle = random.nextDouble() * Math.PI * 2.0;
        double dist = minDist + (maxDist - minDist) * random.nextDouble();
        double sx = target.getX() + Math.cos(angle) * dist;
        double sz = target.getZ() + Math.sin(angle) * dist;

        // Aim at a point near the player so the storm actually passes by, not straight through.
        double tx = target.getX() + (random.nextDouble() - 0.5) * 300.0;
        double tz = target.getZ() + (random.nextDouble() - 0.5) * 300.0;
        double dx = tx - sx;
        double dz = tz - sz;
        double len = Math.max(1.0E-6, Math.sqrt(dx * dx + dz * dz));
        double speed = Config.SPEED_BLOCKS_PER_SECOND.get() / 20.0; // blocks per tick
        float vx = (float) (dx / len * speed);
        float vz = (float) (dz / len * speed);

        int rMin = Math.min(Config.RADIUS_MIN.get(), Config.RADIUS_MAX.get());
        int rMax = Math.max(Config.RADIUS_MIN.get(), Config.RADIUS_MAX.get());
        float radius = rMin + (rMax - rMin) * random.nextFloat();

        int dMin = Math.min(Config.DURATION_MIN_MINUTES.get(), Config.DURATION_MAX_MINUTES.get());
        int dMax = Math.max(Config.DURATION_MIN_MINUTES.get(), Config.DURATION_MAX_MINUTES.get());
        int maxAge = (int) ((dMin + (dMax - dMin) * random.nextDouble()) * 1200.0);

        // In test mode the storm starts partway through its life, so it is already at full strength when you join.
        int startAge = Config.TEST_MODE.get() ? (int) (maxAge * 0.25) : 0;
        StormCell cell = new StormCell(data.allocateId(), sx, sz, vx, vz, radius, startAge, maxAge);
        data.storms().add(cell);
        XWeatherMod.LOGGER.info("Storm {} formed at ({}, {}), radius {}, lifetime {} min",
                cell.id, (int) sx, (int) sz, (int) radius, maxAge / 1200);
    }

    /** Wind at a position, from the level's current storms and tornadoes (server side). */
    public static WindField.Wind windAt(ServerLevel level, double x, double z) {
        StormSavedData data = level.getDataStorage().computeIfAbsent(StormSavedData.TYPE);
        List<StormSnapshot> storms = new ArrayList<>(data.storms().size());
        for (StormCell cell : data.storms()) {
            storms.add(cell.snapshot());
        }
        List<TornadoSnapshot> tornadoes = new ArrayList<>(data.tornadoes().size());
        for (TornadoCell cell : data.tornadoes()) {
            tornadoes.add(cell.snapshot());
        }
        return WindField.sample(storms, tornadoes, 0.0, level.getGameTime(), x, z);
    }

    private static void sync(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        StormSavedData data = level.getDataStorage().computeIfAbsent(StormSavedData.TYPE);
        List<StormSnapshot> storms = new ArrayList<>(data.storms().size());
        for (StormCell cell : data.storms()) {
            storms.add(cell.snapshot());
        }
        List<TornadoSnapshot> tornadoes = new ArrayList<>(data.tornadoes().size());
        for (TornadoCell cell : data.tornadoes()) {
            tornadoes.add(cell.snapshot());
        }
        StormSyncPayload payload = new StormSyncPayload(storms, tornadoes);
        for (ServerPlayer player : players) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
