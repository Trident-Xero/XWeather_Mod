package com.tridentxero.xweathermod.storm;

import java.util.ArrayList;
import java.util.List;

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
 * OPTIONAL, OFF BY DEFAULT (terrain.enabled = false). Lets tornadoes tear chunks out of structures.
 * Completely separate from {@link EntityForces}, which never touches blocks.
 *
 * How a step works, per tornado at or above terrain.minTier and at least half intensity:
 *  1. Chunks. Up to terrain.maxChunksPerStep roughly spherical, ragged chunks are carved at random points inside
 *     the tornado's ground footprint (0.8 x size). Chunk radius grows with the effective wind (wind x intensity):
 *     1 block at 65 mph, about 2 at EF2 (111), 3 at EF4 (166), 4 at EF5 (201), never above terrain.maxChunkRadius.
 *  2. Pick-up fraction. Only a FRACTION of the removed blocks is lifted into the tornado: 10% just above 65 mph
 *     rising to 70% at 201 mph, scaled down for harder blocks. Lifted blocks are sent to clients as orbiting
 *     debris; the rest just shatter. Nothing is placed back: debris is a visual only and never lands as a block.
 *  3. Budget. At most terrain.maxBlocksPerStep blocks per tornado per step, only in chunks that are already
 *     loaded (nothing is force-loaded).
 *
 * What a tornado can remove, by tier (see {@link #breakable}):
 *    EF0+: leaves, flowers, crops, replaceable plants, glass.
 *    EF2+: logs, planks, wood doors/fences/stairs/slabs/trapdoors, wool.
 *    EF3+: any other block with hardness <= 2.0.   EF4+: <= 3.5.   EF5: <= 8.0.
 *  Natural terrain (dirt, sand, base stone), block entities (chests, furnaces, signs...) and indestructible blocks
 *  (bedrock, negative hardness, wither-immune) are NEVER removed.
 *
 * NOT implemented yet: claim and spawn-protection integration, mobGriefing game rule. Leave terrain.enabled off on
 * servers that depend on those.
 *
 * UNVERIFIED against a compile on 26.1.2: BlockState#getDestroySpeed(BlockGetter, BlockPos), BlockState#hasBlockEntity,
 * the BlockTags constants, ServerLevel#removeBlock(BlockPos, boolean), ServerLevel#hasChunkAt(BlockPos),
 * Block#getId(BlockState).
 */
public final class TerrainDamage {
    private static final double SEND_RANGE = 320.0;

    private TerrainDamage() {
    }

    public static void tick(ServerLevel level, StormSavedData data) {
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
            carve(level, t, tier, random);
        }
    }

    /** Chunk radius in blocks for an effective wind in mph (wind x intensity). */
    static double chunkRadius(double effectiveMph) {
        double r = 1.0 + (effectiveMph - 65.0) / 45.0;
        return Math.max(1.0, Math.min(Config.TERRAIN_MAX_CHUNK_RADIUS.get(), r));
    }

    /** Fraction of removed blocks that gets lifted into the tornado, for an effective wind in mph. */
    static double liftFraction(double effectiveMph) {
        double f = 0.1 + 0.6 * (effectiveMph - 65.0) / 136.0;
        return Math.max(0.1, Math.min(0.7, f));
    }

    private static void carve(ServerLevel level, TornadoCell t, TornadoTier tier, RandomSource random) {
        double effective = t.windMph * t.intensity();
        double radius = chunkRadius(effective);
        double lift = liftFraction(effective);
        double footprint = Math.max(2.0, t.size * 0.8);
        int budget = Config.TERRAIN_MAX_BLOCKS_PER_STEP.get();
        int chunks = Config.TERRAIN_MAX_CHUNKS_PER_STEP.get();
        int reach = (int) Math.ceil(radius);

        List<DebrisPayload.Piece> pieces = new ArrayList<>();
        int removed = 0;
        int chunksDone = 0;
        // Up to 3 attempts per wanted chunk: some random points are unloaded or have nothing breakable.
        for (int attempt = 0; attempt < chunks * 3 && chunksDone < chunks && removed < budget; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double r = footprint * Math.sqrt(random.nextDouble());
            int cx = (int) Math.floor(t.x + Math.cos(angle) * r);
            int cz = (int) Math.floor(t.z + Math.sin(angle) * r);
            if (!level.hasChunkAt(new BlockPos(cx, 64, cz))) {
                continue; // never load chunks for weather
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz) - 1;
            double centerY = top - radius * 0.25; // bite mostly out of the top of whatever stands here
            int carvedHere = 0;
            for (int dx = -reach; dx <= reach && removed < budget; dx++) {
                for (int dz = -reach; dz <= reach && removed < budget; dz++) {
                    for (int dy = -reach; dy <= reach && removed < budget; dy++) {
                        double d2 = dx * dx + dy * dy + dz * dz;
                        // Ragged edge: each block's own limit wobbles between 70% and 105% of the radius.
                        double limit = radius * (0.7 + 0.35 * random.nextDouble());
                        if (d2 > limit * limit) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(cx + dx, (int) Math.floor(centerY) + dy, cz + dz);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir() || !breakable(state, level, pos, tier)) {
                            continue;
                        }
                        float hardness = Math.max(0.0F, state.getDestroySpeed(level, pos));
                        double softness = Math.max(0.3, 1.0 - hardness / 10.0);
                        boolean lifted = random.nextDouble() < lift * softness;
                        int stateId = Block.getId(state);
                        level.removeBlock(pos, false);
                        removed++;
                        carvedHere++;
                        pieces.add(new DebrisPayload.Piece(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F,
                                stateId, lifted));
                    }
                }
            }
            if (carvedHere > 0) {
                chunksDone++;
            }
        }
        if (removed > 0) {
            XWeatherMod.LOGGER.debug("Tornado {} tore out {} blocks (chunk radius {}, {}% lifted)", t.id, removed,
                    String.format("%.1f", radius), (int) (lift * 100));
            send(level, t, pieces);
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

    /** Whether a tornado of this tier is allowed to remove this block. */
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
