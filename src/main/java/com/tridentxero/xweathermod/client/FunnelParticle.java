package com.tridentxero.xweathermod.client;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.RandomSource;

/**
 * One puff of a tornado: funnel body, ground dust or cloud-base collar (see {@link FunnelSpawner}). Client only.
 *
 * It does not move by velocity. Every tick it re-computes its position from the tornado's current state
 * ({@link FunnelSpawner#view(int)}): it orbits the tornado's centre at a fixed height fraction, spinning faster
 * near the ground, with the orbit radius following the funnel profile, lean and sway. So the funnel follows the
 * tornado exactly and disappears with it.
 */
public class FunnelParticle extends SingleQuadParticle {
    /** Live puff count, used by the spawner as a budget. */
    public static final AtomicInteger LIVE = new AtomicInteger();

    private static final int FADE_IN_TICKS = 8;
    private static final int FADE_OUT_TICKS = 10;
    /** When the tornado disappears (dies, or you move out of range) its puffs fade out over this many ticks. */
    private static final int ORPHAN_FADE_TICKS = 12;

    private final int tornadoId;
    /** 0 = ground, 1 = cloud base. */
    private final float heightFraction;
    private final int kind;
    private final float param;
    /** Height noise for wall and tail puffs (-0.5..1). */
    private final float param2;
    private final float maxAlpha;
    private double angle;
    /** -1 while the tornado exists; counts up once it is gone. */
    private int orphanTicks = -1;

    public FunnelParticle(ClientLevel level, int tornadoId, double x, double y, double z,
                          float heightFraction, int kind, float param, float param2, double startAngle,
                          SpriteSet spriteSet, RandomSource random) {
        super(level, x, y, z, spriteSet.first());
        this.tornadoId = tornadoId;
        this.heightFraction = heightFraction;
        this.kind = kind;
        this.param = param;
        this.param2 = param2;
        this.angle = startAngle;

        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
        this.friction = 1.0F;
        this.gravity = 0.0F;
        this.hasPhysics = false;

        this.lifetime = 80 + random.nextInt(60);
        this.alpha = 0.0F;
        this.roll = random.nextFloat() * 6.2831855F;
        this.oRoll = this.roll;

        float h = heightFraction;
        FunnelSpawner.TornadoView view = FunnelSpawner.view(tornadoId);
        double size = view != null ? view.size() : 20.0;
        double r = view != null ? FunnelSpawner.radiusAt(view, h, kind, param) : size * 0.3;
        float scale = ClientConfig.FUNNEL_PARTICLE_SCALE.get().floatValue();

        if (kind == FunnelSpawner.KIND_DUST) {
            this.maxAlpha = 0.45F + random.nextFloat() * 0.2F;
            this.setColor(0.42F, 0.35F, 0.27F);
            this.quadSize = (float) Math.max(1.5, Math.min(7.0, r * 0.22)) * (0.85F + random.nextFloat() * 0.3F) * scale;
        } else if (kind == FunnelSpawner.KIND_COLLAR) {
            // Wall cloud: darkest in the middle, lighter and thinner toward the ragged rim; detached scud is smallest.
            float f = Math.min(1.25F, param);
            // Much darker than the main cloud layer so the lowered base stands out against it.
            float c = 0.09F + 0.14F * Math.min(1.0F, f);
            this.setColor(c - 0.01F, c, c + 0.035F);
            this.maxAlpha = (0.95F - 0.3F * Math.min(1.0F, f * f)) * (0.9F + random.nextFloat() * 0.1F);
            double wall = view != null ? FunnelSpawner.wallRadius(view) : 40.0;
            float sizeBase = (float) Math.max(9.0, Math.min(20.0, wall * 0.2));
            this.quadSize = sizeBase * (f > 1.0F ? 0.55F : 1.0F) * (0.8F + random.nextFloat() * 0.45F) * scale;
        } else if (kind == FunnelSpawner.KIND_TAIL) {
            this.maxAlpha = 0.55F + random.nextFloat() * 0.2F;
            this.setColor(0.36F, 0.38F, 0.43F);
            double wall = view != null ? FunnelSpawner.wallRadius(view) : 40.0;
            this.quadSize = (float) Math.max(4.0, Math.min(9.0, wall * 0.09)) * (0.8F + random.nextFloat() * 0.4F) * scale;
        } else {
            this.maxAlpha = 0.62F + random.nextFloat() * 0.2F;
            // Dusty brown at the base, light grey in the middle, dark slate toward the cloud.
            float baseT = Math.max(0.0F, 1.0F - h * 3.0F);
            float topT = Math.max(0.0F, (h - 0.55F) / 0.45F);
            float mid = 1.0F - baseT - topT;
            float red = 0.40F * baseT + 0.54F * mid + 0.30F * topT;
            float green = 0.34F * baseT + 0.56F * mid + 0.32F * topT;
            float blue = 0.28F * baseT + 0.60F * mid + 0.36F * topT;
            this.setColor(red, green, blue);
            this.quadSize = (float) Math.max(0.9, Math.min(6.0, r * 0.28)) * (0.85F + random.nextFloat() * 0.3F) * scale;
        }

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
        float fade = smooth(Math.min(this.age / (float) FADE_IN_TICKS,
                (this.lifetime - this.age) / (float) FADE_OUT_TICKS));

        if (view == null) {
            // Tornado gone or out of range: stop moving and fade out instead of vanishing.
            this.orphanTicks = Math.max(0, this.orphanTicks) + 1;
            if (this.orphanTicks >= ORPHAN_FADE_TICKS) {
                remove();
                return;
            }
            this.alpha = this.maxAlpha * fade * (1.0F - this.orphanTicks / (float) ORPHAN_FADE_TICKS);
            return;
        }
        this.orphanTicks = -1;

        if (this.kind == FunnelSpawner.KIND_COLLAR || this.kind == FunnelSpawner.KIND_TAIL) {
            tickCloud(view, fade);
            return;
        }

        double h = this.heightFraction;
        double r = FunnelSpawner.radiusAt(view, h, this.kind, this.param);
        // Tangential speed in blocks per tick from the tornado's wind, faster low down; angular speed = speed / radius.
        double speed = view.windMph() * 0.025 * Math.max(0.2, view.intensity()) * (1.35 - 0.55 * h);
        this.angle += speed / Math.max(3.0, r);

        double[] pos = FunnelSpawner.place(view, h, this.angle, r, this.kind);
        this.setPos(pos[0], pos[1], pos[2]);

        this.alpha = this.maxAlpha * fade * (float) Math.min(1.0, view.intensity() * 3.0);
    }

    /** Wall cloud turns slowly about the funnel; the tail cloud flows inward toward the wall cloud. */
    private void tickCloud(FunnelSpawner.TornadoView view, float fade) {
        double f = this.param;
        if (this.kind == FunnelSpawner.KIND_TAIL) {
            f = Math.max(0.0, this.param - 0.3 * this.age / (double) this.lifetime); // drift inward along the tail
        } else {
            double wall = FunnelSpawner.wallRadius(view);
            double speed = view.windMph() * 0.025 * Math.max(0.2, view.intensity()) * 0.3;
            this.angle += speed / Math.max(6.0, wall * Math.max(0.3, this.param));
        }
        double[] pos = FunnelSpawner.placeWall(view, this.kind, f, this.angle, this.param2);
        this.setPos(pos[0], pos[1], pos[2]);
        this.alpha = this.maxAlpha * fade * (float) Math.min(1.0, view.intensity() * 3.0);
    }

    /** Smoothstep: eases both ends of a 0..1 ramp so there is no visible start or stop. */
    private static float smooth(float t) {
        float c = Math.max(0.0F, Math.min(1.0F, t));
        return c * c * (3.0F - 2.0F * c);
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
