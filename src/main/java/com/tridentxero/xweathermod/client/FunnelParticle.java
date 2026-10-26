package com.tridentxero.xweathermod.client;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.RandomSource;

/**
 * One puff of a tornado funnel or its ground dust. Client only. Created by {@link FunnelSpawner}.
 *
 * It does not move by velocity. Every tick it re-computes its position from the tornado's current state
 * ({@link FunnelSpawner#view(int)}): it orbits the tornado's centre at a fixed height fraction, with the orbit
 * radius following the funnel profile (narrow at the ground, wide at the cloud). So the funnel follows the
 * tornado exactly, rotates at a speed set by the tornado's wind, and disappears when the tornado does.
 */
public class FunnelParticle extends SingleQuadParticle {
    /** Live puff count, used by the spawner as a budget. */
    public static final AtomicInteger LIVE = new AtomicInteger();

    private static final int FADE_IN_TICKS = 12;
    private static final int FADE_OUT_TICKS = 20;

    private final int tornadoId;
    /** 0 = ground, 1 = cloud base. */
    private final float heightFraction;
    /** True for the wide, brown dust ring at ground level. */
    private final boolean dust;
    private final float radiusJitter;
    private final float maxAlpha;
    private double angle;

    public FunnelParticle(ClientLevel level, int tornadoId, double x, double y, double z,
                          float heightFraction, boolean dust, double startAngle,
                          SpriteSet spriteSet, RandomSource random) {
        super(level, x, y, z, spriteSet.first());
        this.tornadoId = tornadoId;
        this.heightFraction = heightFraction;
        this.dust = dust;
        this.angle = startAngle;
        this.radiusJitter = 0.75F + random.nextFloat() * 0.4F;

        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
        this.friction = 1.0F;
        this.gravity = 0.0F;
        this.hasPhysics = false;

        this.lifetime = 50 + random.nextInt(50);
        this.maxAlpha = dust ? 0.35F + random.nextFloat() * 0.2F : 0.45F + random.nextFloat() * 0.25F;
        this.alpha = 0.0F;
        this.roll = random.nextFloat() * 6.2831855F;
        this.oRoll = this.roll;

        float h = heightFraction;
        if (dust) {
            this.setColor(0.44F, 0.37F, 0.29F);
        } else {
            // Dusty brown at the base, dark grey higher up.
            float t = Math.min(1.0F, h * 2.5F);
            this.setColor(0.42F - 0.12F * t, 0.36F + 0.02F * t, 0.30F + 0.06F * t);
        }
        FunnelSpawner.TornadoView view = FunnelSpawner.view(tornadoId);
        double size = view != null ? view.size() : 20.0;
        double r = size * FunnelSpawner.profile(h) * this.radiusJitter;
        this.quadSize = (float) Math.max(1.4, Math.min(dust ? 16.0 : 13.0, r * (dust ? 0.6 : 0.42)))
                * (0.85F + random.nextFloat() * 0.35F);

        LIVE.incrementAndGet();
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            remove();
            return;
        }
        FunnelSpawner.TornadoView view = FunnelSpawner.view(this.tornadoId);
        if (view == null) {
            remove();
            return;
        }

        double h = this.heightFraction;
        double r = view.size() * FunnelSpawner.profile((float) h) * this.radiusJitter * (this.dust ? 1.8 : 1.0);
        // Tangential speed in blocks per tick from the tornado's wind; angular speed = speed / radius.
        double speed = view.windMph() * 0.025 * Math.max(0.2, view.intensity());
        this.angle += speed / Math.max(3.0, r);

        // Slight lean/kink so the funnel is not a perfect cone.
        double sway = Math.sin(view.time() * 0.03 + h * 3.0) * view.size() * 0.3 * h * h;
        double cx = view.cx() + sway;
        double cz = view.cz() + Math.cos(view.time() * 0.025 + h * 2.0) * view.size() * 0.2 * h * h;

        double px = cx + Math.cos(this.angle) * r;
        double pz = cz + Math.sin(this.angle) * r;
        double py = view.baseY() + h * (view.topY() - view.baseY()) + (this.dust ? 1.0 : 0.0);
        this.setPos(px, py, pz);

        float in = Math.min(1.0F, this.age / (float) FADE_IN_TICKS);
        float out = Math.min(1.0F, (this.lifetime - this.age) / (float) FADE_OUT_TICKS);
        this.alpha = this.maxAlpha * Math.max(0.0F, Math.min(in, out)) * (float) Math.min(1.0, view.intensity() * 3.0);
    }

    @Override
    public void remove() {
        if (!this.removed) {
            LIVE.decrementAndGet();
        }
        super.remove();
    }

    @Override
    protected SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.TRANSLUCENT;
    }
}
