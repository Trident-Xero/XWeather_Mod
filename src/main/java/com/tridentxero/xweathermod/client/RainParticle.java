package com.tridentxero.xweathermod.client;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.RandomSource;

/**
 * One raindrop streak. Client only. Falls at a fixed speed and disappears when it reaches the surface it was
 * spawned over: {@link RainSpawner} computes that surface from the world's motion-blocking heightmap and passes
 * the exact lifetime, so rain stops at roofs instead of falling through them.
 *
 * Drift follows the wind. The streak is tilted to match by rolling the billboard each tick, using the sideways
 * part of the drift as seen from the player's view direction.
 *
 * Known limitation: this is still a camera-facing billboard, so streaks look screen-vertical when you pitch the
 * camera instead of staying world-vertical. Fixing that needs an extract() override whose render-state type
 * (QuadParticleRenderState) lives in a package I could not confirm for 26.1.2.
 *
 * UNVERIFIED against a compile: the inherited Particle fields roll and oRoll in 26.1.2.
 */
public class RainParticle extends SingleQuadParticle {
    /** Blocks per tick the drop falls. */
    public static final float FALL_SPEED = 0.9F;

    /** Live drop count, used by the spawner as a budget. */
    public static final AtomicInteger LIVE = new AtomicInteger();

    public RainParticle(ClientLevel level, double x, double y, double z,
                        double driftX, double driftZ, int lifetimeTicks, SpriteSet spriteSet, RandomSource random) {
        super(level, x, y, z, spriteSet.first());
        this.xd = driftX;
        this.yd = -FALL_SPEED;
        this.zd = driftZ;
        this.friction = 1.0F;
        this.gravity = 0.0F;
        this.hasPhysics = false;

        this.quadSize = 0.28F + random.nextFloat() * 0.1F; // half-size of the quad; the sprite is a thin vertical line
        this.lifetime = Math.max(1, lifetimeTicks);
        this.alpha = 0.55F + random.nextFloat() * 0.2F;
        // The texture carries the colour (vanilla-style pale blue-grey), so no tint here.
        this.setColor(1.0F, 1.0F, 1.0F);

        LIVE.incrementAndGet();
    }

    @Override
    public void tick() {
        super.tick();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // Camera right vector from the view yaw (Minecraft yaw 0 faces +z, right is -x).
        double yaw = Math.toRadians(mc.player.getYRot());
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double lateral = this.xd * rightX + this.zd * rightZ;
        float tilt = (float) Math.atan2(lateral, FALL_SPEED);
        this.oRoll = this.roll;
        this.roll = ClientConfig.RAIN_INVERT_TILT.get() ? -tilt : tilt;
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
