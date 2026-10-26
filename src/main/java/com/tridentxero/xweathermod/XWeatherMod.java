package com.tridentxero.xweathermod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.tridentxero.xweathermod.particle.ModParticles;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Common entrypoint. Loads on both client and dedicated server, so it must never
 * touch client-only classes. Everything else registers itself through
 * {@code @EventBusSubscriber} classes (network registration, server tick, commands).
 */
@Mod(XWeatherMod.MOD_ID)
public class XWeatherMod {
    public static final String MOD_ID = "xweathermod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public XWeatherMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        ModParticles.PARTICLES.register(modEventBus);
        LOGGER.info("XWeather Mod (Stage 1b) loaded");
    }
}
