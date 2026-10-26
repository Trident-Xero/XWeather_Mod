package com.tridentxero.xweathermod.storm;

/**
 * EF-INSPIRED GAMEPLAY TIERS. These are game categories chosen from the wind speed you give the command.
 * They are NOT a scientific or damage-survey assessment. Thresholds follow the commonly published EF
 * wind bands (mph) purely as a familiar scale, and can be made configurable later.
 */
public enum TornadoTier {
    EF0("EF0", 65),
    EF1("EF1", 86),
    EF2("EF2", 111),
    EF3("EF3", 136),
    EF4("EF4", 166),
    EF5("EF5", 201);

    public final String label;
    public final int minMph;

    TornadoTier(String label, int minMph) {
        this.label = label;
        this.minMph = minMph;
    }

    /** Winds below 65 mph are reported as EF0 for gameplay purposes (a weak tornado). */
    public static TornadoTier fromWind(double mph) {
        TornadoTier result = EF0;
        for (TornadoTier tier : values()) {
            if (mph >= tier.minMph) {
                result = tier;
            }
        }
        return result;
    }
}
