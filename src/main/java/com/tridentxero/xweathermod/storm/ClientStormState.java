package com.tridentxero.xweathermod.storm;

import java.util.List;

/**
 * Latest storm and tornado lists received from the server. Plain data holder, so it is safe to exist on
 * a dedicated server (it is simply never written there). The renderer (Stage 1b) reads it.
 */
public final class ClientStormState {
    private static volatile List<StormSnapshot> storms = List.of();
    private static volatile List<TornadoSnapshot> tornadoes = List.of();
    private static volatile long lastUpdateNanos = 0L;

    private ClientStormState() {
    }

    public static void update(List<StormSnapshot> newStorms, List<TornadoSnapshot> newTornadoes) {
        storms = List.copyOf(newStorms);
        tornadoes = List.copyOf(newTornadoes);
        lastUpdateNanos = System.nanoTime();
    }

    public static List<StormSnapshot> storms() {
        return storms;
    }

    public static List<TornadoSnapshot> tornadoes() {
        return tornadoes;
    }

    public static long lastUpdateNanos() {
        return lastUpdateNanos;
    }

    public static void clear() {
        storms = List.of();
        tornadoes = List.of();
    }
}
