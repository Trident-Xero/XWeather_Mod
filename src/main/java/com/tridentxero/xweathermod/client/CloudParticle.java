package com.tridentxero.xweathermod.client;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.RandomSource;

/**
 * One large camera-facing cloud puff, drawn with one of the supplied textures.
 * Client only. Created by {@link CloudSpawner}; it drifts with the storm, fades in and out, and is tinted
 * darker for stronger storms.
 *
 * "darkness" (0..1) is passed in the constructor's y-velocity slot, because the particle provider interface only
 * carries position and velocity. The constructor consumes it and zeroes the y-velocity.
 */
public class CloudParticle extends SingleQuadParticle {
    /** Live puff count, used by the spawner as a budget. */
    public static final AtomicInteger LIVE = new AtomicInteger();

    private static final int FADE_TICKS = 60;

    /** Set by {@link CloudSpawner} while no storm exists: every live puff then fades out within about 30 ticks. */
    public static volatile boolean FADE_ALL = false;

    private final float maxAlpha;

    public CloudParticle(ClientLevel level, double x, double y, double z,
                         double xd, double darkness, double zd, SpriteSet spriteSet, RandomSource random) {
        super(level, x, y, z, spriteSet.first());

        float dark = (float) Math.max(0.0, Math.min(1.0, darkness));
        this.xd = xd;
        this.yd = 0.0;
        this.zd = zd;
        this.friction = 1.0F;   // keep drifting at the storm's speed
        this.gravity = 0.0F;
        this.hasPhysics = false;

        float lightness = 1.0F - 0.65F * dark;
        this.setColor(lightness, lightness, lightness);

        this.quadSize = 34.0F + random.nextFloat() * 44.0F; // half-width in blocks
        this.lifetime = 400 + random.nextInt(300);
        this.maxAlpha = 0.50F + 0.30F * dark;
        this.alpha = 0.0F;
        this.roll = random.nextFloat() * ((float) Math.PI * 2.0F);
        this.oRoll = this.roll;
        this.setSize(this.quadSize, 4.0F);

        LIVE.incrementAndGet();
    }

    @Override
    public void tick() {
        super.tick();
        if (FADE_ALL && this.age < this.lifetime - 30) {
            this.age = this.lifetime - 30;
        }
        float in = Math.min(1.0F, this.age / (float) FADE_TICKS);
        float out = Math.min(1.0F, (this.lifetime - this.age) / (float) FADE_TICKS);
        this.alpha = this.maxAlpha * Math.max(0.0F, Math.min(in, out));
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
