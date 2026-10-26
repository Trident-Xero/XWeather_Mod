package com.tridentxero.xweathermod.client;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * Client only. Like the cloud provider, its main job is to hand the loaded sprite set to the spawner,
 * which creates the drops itself.
 */
public class RainParticleProvider implements ParticleProvider<SimpleParticleType> {
    public static volatile SpriteSet SPRITES = null;

    private final SpriteSet spriteSet;

    public RainParticleProvider(SpriteSet spriteSet) {
        this.spriteSet = spriteSet;
        SPRITES = spriteSet;
    }

    @Override
    @Nullable
    public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                   double x, double y, double z,
                                   double xd, double yd, double zd, RandomSource random) {
        return new RainParticle(level, x, y, z, xd, zd, 40, this.spriteSet, random);
    }
}
