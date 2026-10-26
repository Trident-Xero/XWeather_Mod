package com.tridentxero.xweathermod.net;

import com.tridentxero.xweathermod.XWeatherMod;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Common-side payload registration (runs on client and dedicated server).
 * The client-side handler is registered separately in XWeatherModClient so no client class loads here.
 *
 * UNVERIFIED against a compile: the 26.1 docs show playBidirectional(type, codec, serverHandler) and a
 * separate RegisterClientPayloadHandlersEvent. playToClient(type, codec) without a handler is my reading of
 * that split. If the compiler wants a handler argument, tell me the error and I will adjust.
 */
@EventBusSubscriber(modid = XWeatherMod.MOD_ID)
public final class ModNetwork {
    private ModNetwork() {
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(StormSyncPayload.TYPE, StormSyncPayload.STREAM_CODEC);
    }
}
