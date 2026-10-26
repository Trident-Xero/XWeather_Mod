package com.tridentxero.xweathermod.client;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * Client only. The game builds one of these per cloud particle type after loading the particle descriptions.
 * Its main job in this mod is to hand over the loaded sprite sets: {@link CloudSpawner} creates puffs itself
 * (see the note there), using these.
 */
public class CloudParticleProvider implements ParticleProvider<SimpleParticleType> {
    /** Latest sprite sets from the game (refreshed on resource reload). */
    public static volatile SpriteSet SOFT_SPRITES = null;
    public static volatile SpriteSet BANDED_SPRITES = null;

    private final SpriteSet spriteSet;

    public CloudParticleProvider(SpriteSet spriteSet, boolean banded) {
        this.spriteSet = spriteSet;
        if (banded) {
            BANDED_SPRITES = spriteSet;
        } else {
            SOFT_SPRITES = spriteSet;
        }
    }

    @Override
    @Nullable
    public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                   double x, double y, double z,
                                   double xd, double yd, double zd, RandomSource random) {
        return new CloudParticle(level, x, y, z, xd, yd, zd, this.spriteSet, random);
    }
}
