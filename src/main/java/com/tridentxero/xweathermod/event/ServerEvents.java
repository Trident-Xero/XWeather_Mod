package com.tridentxero.xweathermod.event;

import com.tridentxero.xweathermod.Config;
import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.storm.StormSimulator;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Game-bus server events. Common code only, safe on a dedicated server.
 *
 * UNVERIFIED against a compile: ServerTickEvent.Post is the 1.21.x name; I have not confirmed it is unchanged in 26.1.
 */
@EventBusSubscriber(modid = XWeatherMod.MOD_ID)
public final class ServerEvents {
    private ServerEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Config.ENABLED.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int tickCount = server.getTickCount();
        for (ServerLevel level : server.getAllLevels()) {
            StormSimulator.tickLevel(level, tickCount);
        }
    }
}
