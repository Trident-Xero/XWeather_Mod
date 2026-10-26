package com.tridentxero.xweathermod.particle;

import com.tridentxero.xweathermod.XWeatherMod;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Particle types. Common code (the registry must exist on the server too); the particle classes live in the
 * client package.
 *
 * cloud_soft   -> textures/particle/cloud_soft.png   (your 64x64 soft puff)
 * cloud_banded -> textures/particle/cloud_banded.png (your 256x256 banded puff)
 * rain_streak  -> textures/particle/rain_streak.png  (PLACEHOLDER art generated for development: replace it)
 *
 * overrideLimiter=true: these ignore the player's "Particles: Decreased/Minimal" video setting, because they are
 * the weather itself. This mod has its own density options instead.
 */
public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, XWeatherMod.MOD_ID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> CLOUD_SOFT =
            PARTICLES.register("cloud_soft", () -> new SimpleParticleType(true));

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> CLOUD_BANDED =
            PARTICLES.register("cloud_banded", () -> new SimpleParticleType(true));

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> RAIN_STREAK =
            PARTICLES.register("rain_streak", () -> new SimpleParticleType(true));

    private ModParticles() {
    }
}
