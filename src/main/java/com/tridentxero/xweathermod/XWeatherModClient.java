package com.tridentxero.xweathermod;

import com.tridentxero.xweathermod.client.ClientConfig;
import com.tridentxero.xweathermod.client.ClientPayloadHandler;
import com.tridentxero.xweathermod.client.CloudParticleProvider;
import com.tridentxero.xweathermod.client.RainParticleProvider;
import com.tridentxero.xweathermod.net.DebrisPayload;
import com.tridentxero.xweathermod.net.StormSyncPayload;
import com.tridentxero.xweathermod.particle.ModParticles;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

// This class does not load on dedicated servers, so client classes are safe here.
@Mod(value = XWeatherMod.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = XWeatherMod.MOD_ID, value = Dist.CLIENT)
public class XWeatherModClient {
    public XWeatherModClient(ModContainer container) {
        // Client-only options: config/xweathermod-client.toml
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        // Mods screen -> XWeather Mod -> Config
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(StormSyncPayload.TYPE, ClientPayloadHandler::handle);
        event.register(DebrisPayload.TYPE, ClientPayloadHandler::handleDebris);
    }

    @SubscribeEvent
    static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.CLOUD_SOFT.get(), sprites -> new CloudParticleProvider(sprites, false));
        event.registerSpriteSet(ModParticles.CLOUD_BANDED.get(), sprites -> new CloudParticleProvider(sprites, true));
        event.registerSpriteSet(ModParticles.RAIN_STREAK.get(), RainParticleProvider::new);
    }
}
