package com.tridentxero.xweathermod.storm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.tridentxero.xweathermod.Config;
import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.net.DebrisPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * OPTIONAL, OFF BY DEFAULT (terrain.enabled = false). Lets tornadoes chip away at blocks one bite at a time.
 * Completely separate from {@link EntityForces}, which never touches blocks.
 *
 * How it works. Minecraft blocks are whole cubes, so a block cannot literally have a corner missing (that would
 * need custom block models). Instead every block has a damage meter, shown with vanilla's own crack overlay:
 *  - Each simulation step a tornado (at or above terrain.minTier and at least half intensity) lands up to
 *    terrain.hitsPerStep "bites" on individual blocks inside its ground footprint (0.8 x size).
 *  - A bite takes a fraction of a block: 10% at 65 mph growing to 60% at 201 mph (wind x intensity). Harder blocks
 *    lose less per bite (leaves go in one bite, planks in a few, stone in more). The crack overlay (stages 0 to 9)
 *    grows with the damage, and chips of the block's material fly off.
 *  - Only a FRACTION of each bite is picked up by the tornado: 10% just above 65 mph rising to 70% at 201 mph,
 *    less for harder blocks. Picked-up chips orbit the funnel; the rest just break off and fall.
 *  - When a block's damage reaches 100% it is removed and shatters (and is itself picked up with the same chance).
 *  - Damage that is not added to for 30 seconds fades and the cracks clear. Damage is kept in memory only: it
 *    resets when the server restarts.
 *
 * Bounds: terrain.hitsPerStep bites and terrain.maxBlocksPerStep fully broken blocks per tornado per step, only in
 * chunks that are already loaded (nothing is force-loaded), at most 3000 damaged blocks tracked per dimension.
 *
 * What a tornado can damage, by tier (see {@link #breakable}):
 *    EF0+: leaves, flowers, crops, replaceable plants, glass.
 *    EF2+: logs, planks, wood doors/fences/stairs/slabs/trapdoors, wool.
 *    EF3+: any other block with hardness <= 2.0.   EF4+: <= 3.5.   EF5: <= 8.0.
 *  Natural terrain (dirt, sand, base stone), block entities (chests, furnaces, signs...) and indestructible blocks
 *  (bedrock, negative hardness, wither-immune) are NEVER touched. Removed blocks never drop items.
 *
 * NOT implemented yet: claim and spawn-protection integration, mobGriefing game rule. Leave terrain.enabled off on
 * servers that depend on those.
 *
 * UNVERIFIED against a compile on 26.1.2: ServerLevel#destroyBlockProgress(int, BlockPos, int), BlockPos#asLong and
 * BlockPos#of(long), BlockState#getDestroySpeed(BlockGetter, BlockPos), BlockState#hasBlockEntity, the BlockTags
 * constants, ServerLevel#removeBlock(BlockPos, boolean), ServerLevel#hasChunkAt(BlockPos), Block#getId(BlockState).
 */
public final class TerrainDamage {
    private static final int[] DEPTH_BY_TIER = {1, 1, 2, 3, 4, 6};
    private static final double SEND_RANGE = 320.0;
    private static final long DECAY_TICKS = 600L;
    private static final int MAX_TRACKED = 3000;

    /** Damage meter of one block. */
    private static final class Chip {
        float damage;
        long lastHit;
        final int breakerId;

        Chip(int breakerId) {
            this.breakerId = breakerId;
        }
    }

    /** Dimension -> (block position as long -> damage). Server thread only. */
    private static final Map<Object, Map<Long, Chip>> DAMAGE = new HashMap<>();

    private TerrainDamage() {
    }

    public static void tick(ServerLevel level, StormSavedData data) {
        Map<Long, Chip> map = DAMAGE.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        prune(level, map);
        if (!Config.TERRAIN_ENABLED.get()) {
            return;
        }
        int minTier = Config.TERRAIN_MIN_TIER.get(); // 0..5
        RandomSource random = level.getRandom();
        for (TornadoCell t : data.tornadoes()) {
            TornadoTier tier = t.tier();
            if (tier.ordinal() < minTier || t.intensity() < 0.5F) {
                continue;
            }
            bite(level, map, t, tier, random);
        }
    }

    /** Fraction of a block taken by one bite, for an effective wind in mph (wind x intensity). */
    static double biteFraction(double effectiveMph) {
        double f = 0.1 + 0.5 * (effectiveMph - 65.0) / 136.0;
        return Math.max(0.1, Math.min(0.6, f));
    }

    /** Fraction of a bite that is picked up by the tornado, for an effective wind in mph. */
    static double liftFraction(double effectiveMph) {
        double f = 0.1 + 0.6 * (effectiveMph - 65.0) / 136.0;
        return Math.max(0.1, Math.min(0.7, f));
    }

    /** Unique, stable id per block position for the crack overlay (the client keeps one overlay per id). */
    private static int breakerId(BlockPos pos) {
        long l = pos.asLong();
        return 0x40000000 | (int) ((l ^ (l >>> 32)) & 0x3FFFFFFF);
    }

    private static void bite(ServerLevel level, Map<Long, Chip> map, TornadoCell t, TornadoTier tier, RandomSource random) {
        double effective = t.windMph * t.intensity();
        double bite = biteFraction(effective);
        double lift = liftFraction(effective);
        double footprint = Math.max(2.0, t.size * 0.8);
        int hits = Config.TERRAIN_HITS_PER_STEP.get();
        int breakBudget = Config.TERRAIN_MAX_BLOCKS_PER_STEP.get();
        int depth = DEPTH_BY_TIER[tier.ordinal()];
        long now = level.getGameTime();

        List<DebrisPayload.Piece> pieces = new ArrayList<>();
        int landed = 0;
        int broken = 0;
        // Up to 3 attempts per bite: some random points are unloaded or have nothing breakable.
        for (int attempt = 0; attempt < hits * 3 && landed < hits; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double r = footprint * Math.sqrt(random.nextDouble());
            int x = (int) Math.floor(t.x + Math.cos(angle) * r);
            int z = (int) Math.floor(t.z + Math.sin(angle) * r);
            if (!level.hasChunkAt(new BlockPos(x, 64, z))) {
                continue; // never load chunks for weather
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            BlockPos pos = new BlockPos(x, top - random.nextInt(depth), z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !breakable(state, level, pos, tier)) {
                continue;
            }

            float hardness = Math.max(0.0F, state.getDestroySpeed(level, pos));
            double toughness = Math.max(0.25, Math.min(4.0, hardness / 2.0));
            double softness = Math.max(0.3, 1.0 - hardness / 10.0);
            boolean lifted = random.nextDouble() < lift * softness;
            int stateId = Block.getId(state);
            float cx = pos.getX() + 0.5F + (random.nextFloat() - 0.5F) * 0.9F;
            float cy = pos.getY() + 0.5F + (random.nextFloat() - 0.5F) * 0.9F;
            float cz = pos.getZ() + 0.5F + (random.nextFloat() - 0.5F) * 0.9F;

            Chip chip = map.get(pos.asLong());
            if (chip == null) {
                if (map.size() >= MAX_TRACKED) {
                    continue; // tracking budget full: skip new blocks until old damage fades
                }
                chip = new Chip(breakerId(pos));
                map.put(pos.asLong(), chip);
            }
            chip.damage += (float) (bite / toughness);
            chip.lastHit = now;
            landed++;

            if (chip.damage >= 1.0F && broken < breakBudget) {
                level.removeBlock(pos, false);
                level.destroyBlockProgress(chip.breakerId, pos, -1);
                map.remove(pos.asLong());
                broken++;
                pieces.add(new DebrisPayload.Piece(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F,
                        stateId, lifted, 1.0F)); // the block shatters
            } else {
                chip.damage = Math.min(chip.damage, 0.99F);
                level.destroyBlockProgress(chip.breakerId, pos, Math.min(9, (int) (chip.damage * 10.0F)));
                pieces.add(new DebrisPayload.Piece(cx, cy, cz, stateId, lifted, (float) Math.min(1.0, bite / toughness)));
            }
        }
        if (landed > 0) {
            XWeatherMod.LOGGER.debug("Tornado {} landed {} bites, broke {} blocks (bite {}%, {}% picked up)", t.id,
                    landed, broken, (int) (bite * 100), (int) (lift * 100));
            send(level, t, pieces);
        }
    }

    /** Clears damage that has not been added to for a while, or whose block is gone. */
    private static void prune(ServerLevel level, Map<Long, Chip> map) {
        if (map.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        Iterator<Map.Entry<Long, Chip>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Chip> e = it.next();
            BlockPos pos = BlockPos.of(e.getKey());
            boolean stale = now - e.getValue().lastHit > DECAY_TICKS || !Config.TERRAIN_ENABLED.get();
            if (stale || level.getBlockState(pos).isAir()) {
                level.destroyBlockProgress(e.getValue().breakerId, pos, -1);
                it.remove();
            }
        }
    }

    private static void send(ServerLevel level, TornadoCell t, List<DebrisPayload.Piece> pieces) {
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - t.x;
            double dz = player.getZ() - t.z;
            if (dx * dx + dz * dz > SEND_RANGE * SEND_RANGE) {
                continue;
            }
            for (int i = 0; i < pieces.size(); i += DebrisPayload.MAX_PIECES) {
                List<DebrisPayload.Piece> batch =
                        new ArrayList<>(pieces.subList(i, Math.min(pieces.size(), i + DebrisPayload.MAX_PIECES)));
                PacketDistributor.sendToPlayer(player, new DebrisPayload(t.id, batch));
            }
        }
    }

    /** Whether a tornado of this tier is allowed to damage this block. */
    static boolean breakable(BlockState state, ServerLevel level, BlockPos pos, TornadoTier tier) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F || state.hasBlockEntity() || state.is(BlockTags.WITHER_IMMUNE)) {
            return false; // indestructible or has stored contents
        }
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)) {
            return false; // natural terrain
        }
        int tierIndex = tier.ordinal();
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.CROPS)
                || state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.IMPERMEABLE)) {
            return true;
        }
        if (tierIndex >= TornadoTier.EF2.ordinal()
                && (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(BlockTags.WOOL))) {
            return true;
        }
        double limit = switch (tier) {
            case EF3 -> 2.0;
            case EF4 -> 3.5;
            case EF5 -> 8.0;
            default -> 0.0;
        };
        return hardness <= limit;
    }
}
