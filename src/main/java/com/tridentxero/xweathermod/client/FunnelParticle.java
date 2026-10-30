package com.tridentxero.xweathermod.client;

import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Quaternionf;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.util.Mth;
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

    private static final int FADE_IN_TICKS = 3;
    private static final int FADE_OUT_TICKS = 3;
    /** When the tornado disappears (dies, or you move out of range) its puffs fade out over this many ticks. */
    private static final int ORPHAN_FADE_TICKS = 4;

    private final int tornadoId;
    /** 0 = ground, 1 = cloud base. */
    private final float heightFraction;
    private final int kind;
    private final float param;
    /** Height noise for wall and tail puffs (-0.5..1). */
    private final float param2;
    private final float maxAlpha;
    private double angle;
    /** Fixed turn of the sprite about its own normal. Kept here (not in roll) so it never changes with the camera. */
    private final float spin;
    /** Colour before lighting. */
    private float baseR;
    private float baseG;
    private float baseB;
    /** Funnel interior puffs sit inside the sheath; they are darker, like the shadowed core. */
    private final float coreDarkening;
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
        this.coreDarkening = (kind == FunnelSpawner.KIND_BODY && param < 0.9F) ? 0.55F + 0.45F * (param / 0.9F) : 1.0F;

        this.xd = 0.0;
        this.yd = 0.0;
        this.zd = 0.0;
        this.friction = 1.0F;
        this.gravity = 0.0F;
        this.hasPhysics = false;

        this.lifetime = FunnelSpawner.PUFF_LIFE;
        this.alpha = 0.0F;
        this.spin = random.nextFloat() * 6.2831855F;
        this.roll = 0.0F;
        this.oRoll = 0.0F;

        float h = heightFraction;
        FunnelSpawner.TornadoView view = FunnelSpawner.view(tornadoId);
        FunnelSpawner.WallView wallView = FunnelSpawner.wallView(tornadoId); // for wall/tail puffs tornadoId is the STORM id
        double size = view != null ? view.size() : 20.0;
        double r = view != null ? FunnelSpawner.radiusAt(view, h, kind, param) : size * 0.3;
        float scale = ClientConfig.FUNNEL_PARTICLE_SCALE.get().floatValue();

        if (kind == FunnelSpawner.KIND_DUST) {
            this.maxAlpha = 0.9F;
            tint(0.42F, 0.35F, 0.27F);
            this.quadSize = (float) Math.max(1.5, Math.min(7.0, r * 0.22)) * (0.85F + random.nextFloat() * 0.3F) * scale;
        } else if (kind == FunnelSpawner.KIND_COLLAR) {
            // Wall cloud: darkest in the middle, lighter and thinner toward the ragged rim; detached scud is smallest.
            float f = Math.min(1.25F, param);
            // Much darker than the main cloud layer so the lowered base stands out against it.
            // Darkest slate-blue at the lowest point, blending out to the same grey as the main cloud layer at the
            // rim so the wall cloud reads as a lowering of the cloud base, not a separate disc.
            float c = 0.10F + 0.28F * (float) Math.pow(Math.min(1.0F, f), 1.3);
            float layerT = Math.max(0.0F, Math.min(1.0F, (param2 / 1.6F + 1.0F) / 2.0F)); // 0 bottom layer, 1 top
            c *= 0.75F + 0.35F * layerT;
            tint(c - 0.02F, c, c + 0.05F);
            this.maxAlpha = f > 1.0F ? 0.9F : 1.0F;
            // Half-width chosen so neighbouring puffs touch but do not overlap.
            float sizeBase = (float) (wallView != null ? FunnelSpawner.wallHalfSize(wallView) : 5.0);
            this.quadSize = sizeBase * (f > 1.0F ? 0.7F : 1.0F) * scale;
        } else if (kind == FunnelSpawner.KIND_TAIL) {
            this.maxAlpha = 0.9F;
            tint(0.36F, 0.38F, 0.43F);
            double wall = wallView != null ? wallView.R() : 40.0;
            this.quadSize = (float) Math.max(2.5, Math.min(60.0, wall * 0.04)) * scale;
        } else {
            this.maxAlpha = 1.0F;
            // Dusty brown at the base, light grey in the middle, dark slate toward the cloud.
            float baseT = Math.max(0.0F, 1.0F - h * 3.0F);
            float topT = Math.max(0.0F, (h - 0.55F) / 0.45F);
            float mid = 1.0F - baseT - topT;
            float red = 0.40F * baseT + 0.54F * mid + 0.30F * topT;
            float green = 0.34F * baseT + 0.56F * mid + 0.32F * topT;
            float blue = 0.28F * baseT + 0.60F * mid + 0.36F * topT;
            tint(red, green, blue);
            // Half the average gap between neighbouring puffs, so they touch but do not overlap.
            double gap = view != null ? FunnelSpawner.bodySpacing(view) : 2.0;
            this.quadSize = (float) Math.max(0.35, Math.min(40.0, gap * 0.5)) * scale;
        }

        LIVE.incrementAndGet();
    }

    /** Sets the colour and remembers it as the unlit base colour. */
    private void tint(float r, float g, float b) {
        this.baseR = r;
        this.baseG = g;
        this.baseB = b;
        this.setColor(r, g, b);
    }

    /** Direction the light comes from, as an angle in the x/z plane (a fixed low sun from the south-west). */
    private static final double SUN_ANGLE = Math.toRadians(135.0);

    /**
     * Fake lighting so the funnel reads as a solid, round volume: the side facing the sun is brighter and the far
     * side darker, and this rolls round as the puffs orbit. Interior puffs are darker (shadowed core).
     */
    private void applyLighting() {
        float side = (float) Math.cos(this.angle - SUN_ANGLE);                 // -1 away from the sun, +1 facing it
        float k = (0.80F + 0.20F * side) * this.coreDarkening;
        this.setColor(Math.min(1.0F, this.baseR * k), Math.min(1.0F, this.baseG * k), Math.min(1.0F, this.baseB * k));
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
        boolean cloud = this.kind == FunnelSpawner.KIND_COLLAR || this.kind == FunnelSpawner.KIND_TAIL;
        FunnelSpawner.TornadoView view = cloud ? null : FunnelSpawner.view(this.tornadoId);
        FunnelSpawner.WallView wall = cloud ? FunnelSpawner.wallView(this.tornadoId) : null;
        float fade = smooth(Math.min(this.age / (float) FADE_IN_TICKS,
                (this.lifetime - this.age) / (float) FADE_OUT_TICKS));

        if (cloud ? wall == null : view == null) {
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

        if (cloud) {
            tickCloud(wall, fade);
            return;
        }

        double h = this.heightFraction;
        double r = FunnelSpawner.radiusAt(view, h, this.kind, this.param);
        // Tangential speed in blocks per tick from the tornado's wind, faster low down; angular speed = speed / radius.
        double speed = view.windMph() * 0.025 * Math.max(0.2, view.intensity()) * (1.35 - 0.55 * h);
        this.angle += speed / Math.max(3.0, r);

        double[] pos = FunnelSpawner.place(view, h, this.angle, r, this.kind);
        this.setPos(pos[0], pos[1], pos[2]);

        this.alpha = this.maxAlpha * fade;
        if (this.kind == FunnelSpawner.KIND_BODY) {
            applyLighting();
        }
    }

    /** Wall cloud turns slowly as one piece (faster as the storm develops); the tail flows inward toward it. */
    private void tickCloud(FunnelSpawner.WallView w, float fade) {
        double f = this.param;
        if (this.kind == FunnelSpawner.KIND_TAIL) {
            f = Math.max(0.0, this.param - 0.3 * this.age / (double) this.lifetime); // drift inward along the tail
        } else {
            this.angle += 0.004 + 0.012 * w.development(); // radians per tick; the whole bowl turns, so spacing is kept
        }
        double[] pos = FunnelSpawner.placeWall(w, this.kind, f, this.angle, this.param2);
        this.setPos(pos[0], pos[1], pos[2]);
        this.alpha = this.maxAlpha * fade;
    }

    /** Smoothstep: eases both ends of a 0..1 ramp so there is no visible start or stop. */
    private static float smooth(float t) {
        float c = Math.max(0.0F, Math.min(1.0F, t));
        return c * c * (3.0F - 2.0F * c);
    }

    /**
     * Orientation. Vanilla particles always face the camera on every axis, which makes the sprites look stuck to the
     * screen and turn with your view. Here:
     *  - funnel body and dust turn only about the vertical axis to face you (like the rain), so they stay upright;
     *  - wall cloud and tail cloud are flat horizontal sheets fixed in the world, so they do not turn with you at all.
     *
     * UNVERIFIED against a compile: extractRotatedQuad(QuadParticleRenderState, Camera, Quaternionf, float), the
     * same call RainParticle uses.
     */
    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partialTick) {
        Quaternionf rotation = new Quaternionf();
        if (this.kind == FunnelSpawner.KIND_COLLAR || this.kind == FunnelSpawner.KIND_TAIL) {
            // Flat sheet, normal pointing at you vertically (down when you are under it, up when above), so the
            // back face is never the one drawn. Its turn about the vertical axis is fixed in the world.
            Minecraft mcf = Minecraft.getInstance();
            boolean below = mcf.player != null && mcf.player.getY() < this.y;
            rotation.rotationX((float) (below ? Math.PI / 2.0 : -Math.PI / 2.0)).rotateZ(this.spin);
        } else {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                double px = Mth.lerp(partialTick, this.xo, this.x);
                double pz = Mth.lerp(partialTick, this.zo, this.z);
                rotation.rotationY((float) Math.atan2(mc.player.getX() - px, mc.player.getZ() - pz));
            }
            rotation.rotateZ(this.spin);
        }
        this.extractRotatedQuad(state, camera, rotation, partialTick);
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
