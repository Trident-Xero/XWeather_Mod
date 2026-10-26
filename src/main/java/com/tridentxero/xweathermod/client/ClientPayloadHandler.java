package com.tridentxero.xweathermod.client;

import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.net.StormSyncPayload;
import com.tridentxero.xweathermod.storm.ClientStormState;
import com.tridentxero.xweathermod.storm.StormSnapshot;
import com.tridentxero.xweathermod.storm.TornadoSnapshot;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client-only. Only referenced from XWeatherModClient, which never loads on a dedicated server. */
public final class ClientPayloadHandler {
    private static long lastLogNanos = 0L;

    private ClientPayloadHandler() {
    }

    public static void handle(StormSyncPayload payload, IPayloadContext context) {
        ClientStormState.update(payload.storms(), payload.tornadoes());

        // Optional status line (debug.logging in the client config), at most every 10 seconds.
        if (!ClientConfig.DEBUG_LOGGING.get()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastLogNanos > 10_000_000_000L) {
            lastLogNanos = now;
            String stormText = "no storms";
            if (!payload.storms().isEmpty()) {
                StormSnapshot s = payload.storms().get(0);
                stormText = String.format("%d storm(s), first #%d at (%d, %d) intensity %.2f",
                        payload.storms().size(), s.id(), (int) s.x(), (int) s.z(), s.intensity());
            }
            String tornadoText = "no tornadoes";
            if (!payload.tornadoes().isEmpty()) {
                TornadoSnapshot t = payload.tornadoes().get(0);
                tornadoText = String.format("%d tornado(es), first #%d at (%d, %d) %d mph radius %d",
                        payload.tornadoes().size(), t.id(), (int) t.x(), (int) t.z(), (int) t.windMph(), (int) t.size());
            }
            XWeatherMod.LOGGER.info("[client] sync: {}; {}", stormText, tornadoText);
        }
    }
}
