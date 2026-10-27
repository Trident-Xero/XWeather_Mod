package com.tridentxero.xweathermod.client;

import java.util.ArrayList;
import java.util.List;

import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.net.DebrisPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client only. Shows the blocks a tornado has torn out (see DebrisPayload; the blocks are already removed on the
 * server, this is purely visual).
 *
 *  - Pieces that were NOT lifted shatter on the spot into a small burst of block fragments.
 *  - Pieces that WERE lifted become orbiting debris: each one spirals up the funnel (same lean, sway and spin as
 *    the funnel itself), then at the top or after its time is up is flung off along the tangent and falls away.
 *
 * Rendering note (approximation): a lifted piece is a moving point that emits vanilla block-fragment particles
 * (the ones that show when you break a block) as it travels, so you see a stream of chunks of the right material
 * swirling around the funnel, not one solid rotating cube. That uses only stable particle calls. Fragments follow
 * the player's own Particles video setting. Capped by debris.maxPieces.
 *
 * UNVERIFIED against a compile on 26.1.2: Block#stateById(int), BlockParticleOption(ParticleType, BlockState),
 * ParticleTypes.BLOCK, ClientLevel#addParticle(ParticleOptions, double x6).
 */
@EventBusSubscriber(modid = XWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class DebrisSpawner {
    private static final List<Piece> pieces = new ArrayList<>();
    private static final List<DebrisPayload> inbox = new ArrayList<>();
    private static ClientLevel lastLevel = null;

    private DebrisSpawner() {
    }

    private static final class Piece {
        final BlockState state;
        final int tornadoId;
        double angle;
        double h;
        double r;
        final double rise;
        final double wobble;
        int ticks;
        final int maxTicks;
        // Flung phase
        boolean flung = false;
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        int flightTicks = 0;

        Piece(BlockState state, int tornadoId, double angle, double h, double r, RandomSource random) {
            this.state = state;
            this.tornadoId = tornadoId;
            this.angle = angle;
            this.h = h;
            this.r = r;
            this.rise = 0.004 + random.nextDouble() * 0.007;
            this.wobble = 0.9 + random.nextDouble() * 0.5; // each chunk rides a slightly different radius
            this.maxTicks = 90 + random.nextInt(90);
        }
    }

    /** Called from the client payload handler (network thread); queued and handled on the client tick. */
    public static void accept(DebrisPayload payload) {
        synchronized (inbox) {
            inbox.add(payload);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            lastLevel = level;
            pieces.clear();
            synchronized (inbox) {
                inbox.clear();
            }
        }
        if (level == null || mc.isPaused()) {
            return;
        }
        List<DebrisPayload> batch;
        synchronized (inbox) {
            if (inbox.isEmpty()) {
                batch = List.of();
            } else {
                batch = new ArrayList<>(inbox);
                inbox.clear();
            }
        }
        if (ClientConfig.DEBRIS_ENABLED.get()) {
            for (DebrisPayload payload : batch) {
                ingest(level, payload);
            }
        }
        step(level);
    }

    private static void ingest(ClientLevel level, DebrisPayload payload) {
        RandomSource random = level.getRandom();
        FunnelSpawner.TornadoView view = FunnelSpawner.view(payload.tornadoId());
        int max = ClientConfig.DEBRIS_MAX_PIECES.get();
        for (DebrisPayload.Piece p : payload.pieces()) {
            BlockState state = Block.stateById(p.stateId());
            if (state == null || state.isAir()) {
                continue;
            }
            if (!p.lifted() || view == null || pieces.size() >= max) {
                burst(level, state, p.x(), p.y(), p.z(), random);
                continue;
            }
            double dx = p.x() - view.cx();
            double dz = p.z() - view.cz();
            double angle = Math.atan2(dz, dx);
            double baseRadius = view.size() * FunnelSpawner.profile(0.0) * 1.3;
            double r = Math.max(baseRadius, Math.sqrt(dx * dx + dz * dz));
            double h = Math.max(0.0, Math.min(0.25, (p.y() - view.baseY()) / Math.max(1.0, view.topY() - view.baseY())));
            pieces.add(new Piece(state, payload.tornadoId(), angle, h, r, random));
            burst(level, state, p.x(), p.y(), p.z(), random); // a puff where it was ripped out
        }
    }

    private static void burst(ClientLevel level, BlockState state, double x, double y, double z, RandomSource random) {
        BlockParticleOption option = new BlockParticleOption(ParticleTypes.BLOCK, state);
        for (int i = 0; i < 4; i++) {
            level.addParticle(option, x + (random.nextDouble() - 0.5), y + (random.nextDouble() - 0.5),
                    z + (random.nextDouble() - 0.5), (random.nextDouble() - 0.5) * 0.4, random.nextDouble() * 0.3,
                    (random.nextDouble() - 0.5) * 0.4);
        }
    }

    private static void step(ClientLevel level) {
        if (pieces.isEmpty()) {
            return;
        }
        RandomSource random = level.getRandom();
        for (int i = pieces.size() - 1; i >= 0; i--) {
            Piece p = pieces.get(i);
            FunnelSpawner.TornadoView view = FunnelSpawner.view(p.tornadoId);
            if (!p.flung) {
                if (view == null) {
                    pieces.remove(i); // tornado gone or out of range
                    continue;
                }
                p.ticks++;
                p.h += p.rise * (0.6 + 0.8 * view.intensity());
                double targetR = FunnelSpawner.radiusAt(view, p.h, FunnelSpawner.KIND_BODY, 1.0) * 1.15 * p.wobble;
                p.r += (targetR - p.r) * 0.08;
                double speed = view.windMph() * 0.025 * Math.max(0.2, view.intensity()) * (1.35 - 0.55 * p.h);
                p.angle += speed / Math.max(3.0, p.r);
                double[] pos = FunnelSpawner.place(view, p.h, p.angle, p.r, FunnelSpawner.KIND_BODY);
                p.x = pos[0];
                p.y = pos[1];
                p.z = pos[2];
                if (p.h >= 0.8 || p.ticks >= p.maxTicks) {
                    // Throw it off along the tangent, a bit outward and up.
                    double tx = -Math.sin(p.angle);
                    double tz = Math.cos(p.angle);
                    double ox = Math.cos(p.angle);
                    double oz = Math.sin(p.angle);
                    double fling = speed * 1.1 + 0.2;
                    p.vx = tx * fling + ox * 0.25;
                    p.vz = tz * fling + oz * 0.25;
                    p.vy = 0.12 + random.nextDouble() * 0.1;
                    p.flung = true;
                }
            } else {
                p.x += p.vx;
                p.y += p.vy;
                p.z += p.vz;
                p.vy -= 0.045;
                p.vx *= 0.99;
                p.vz *= 0.99;
                p.flightTicks++;
                if (p.flightTicks > 45 || (view != null && p.y < view.baseY())) {
                    burst(level, p.state, p.x, p.y, p.z, random);
                    pieces.remove(i);
                    continue;
                }
            }
            // Trail of fragments marks the chunk's path; every other tick keeps the particle count modest.
            if ((p.ticks + p.flightTicks) % 2 == 0) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, p.state), p.x, p.y, p.z, 0.0, 0.0, 0.0);
            }
        }
    }
}
