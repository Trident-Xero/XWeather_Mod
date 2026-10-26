package com.tridentxero.xweathermod.storm;

import java.util.List;

import com.tridentxero.xweathermod.Config;
import com.tridentxero.xweathermod.XWeatherMod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Server-authoritative lightning. Uses vanilla's LightningBolt entity, so the bolt, flash and thunder sound are
 * the normal ones and every client gets them through vanilla's own entity sync.
 *
 * Rules:
 *  - Strikes only happen near players (within lightning.maxDistanceFromPlayer) and only in chunks that are
 *    already loaded. Nothing is ever force-loaded.
 *  - Rate per storm scales with intensity and size; supercells strike 1.5x as often.
 *  - By default bolts are visual only (vanilla setVisualOnly): they flash and thunder but start no fires and hurt
 *    nobody. Set lightning.realStrikes = true for vanilla behaviour (fire, damage, charged creepers). That is
 *    separate from, and does not touch, the terrain damage option planned for tornadoes.
 *
 * UNVERIFIED against a compile on 26.1.2: EntityType#create(Level, EntitySpawnReason), EntitySpawnReason.TRIGGERED,
 * LightningBolt#setVisualOnly(boolean), Entity#setPos, ServerLevel#hasChunkAt(BlockPos) and the package of
 * EntitySpawnReason.
 */
public final class LightningSimulator {
    private static final int MAX_STEP_TICKS = 40;

    private LightningSimulator() {
    }

    /** Called from the storm step with the elapsed ticks since the previous step. */
    public static void tick(ServerLevel level, StormSavedData data, long dt) {
        if (!Config.LIGHTNING_ENABLED.get() || dt <= 0) {
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        // After a long gap (catch-up) do not fire a burst of bolts: treat it as one normal step.
        double steps = Math.min(dt, MAX_STEP_TICKS);
        RandomSource random = level.getRandom();
        for (StormCell storm : data.storms()) {
            float intensity = storm.intensity();
            if (intensity < 0.35F) {
                continue;
            }
            double perMinute = Config.LIGHTNING_STRIKES_PER_MINUTE.get()
                    * intensity * Math.max(0.5, Math.min(2.0, storm.radius / 200.0))
                    * (storm.supercell ? 1.5 : 1.0);
            double chance = perMinute / 1200.0 * steps;
            if (random.nextDouble() >= chance) {
                continue;
            }
            ServerPlayer player = players.get(random.nextInt(players.size()));
            strikeNear(level, storm, player, random);
        }
    }

    private static void strikeNear(ServerLevel level, StormCell storm, ServerPlayer player, RandomSource random) {
        double maxDist = Config.LIGHTNING_MAX_DISTANCE.get();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double dist = 20.0 + random.nextDouble() * Math.max(0.0, maxDist - 20.0);
        double x = player.getX() + Math.cos(angle) * dist;
        double z = player.getZ() + Math.sin(angle) * dist;
        double dx = x - storm.x;
        double dz = z - storm.z;
        if (dx * dx + dz * dz > (double) storm.radius * storm.radius * 0.64) {
            return; // outside the storm's core
        }
        strikeAt(level, x, z);
    }

    /** Strikes at x, z if that chunk is loaded. Returns false if nothing was spawned. */
    public static boolean strikeAt(ServerLevel level, double x, double z) {
        BlockPos column = BlockPos.containing(x, 64.0, z);
        if (!level.hasChunkAt(column)) {
            return false; // never load chunks for weather
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
        if (bolt == null) {
            return false;
        }
        bolt.setPos(x, y, z);
        bolt.setVisualOnly(!Config.LIGHTNING_REAL_STRIKES.get());
        boolean added = level.addFreshEntity(bolt);
        if (added) {
            XWeatherMod.LOGGER.debug("Lightning at ({}, {}, {})", (int) x, y, (int) z);
        }
        return added;
    }
}
