package com.tridentxero.xweathermod.storm;

import java.util.List;

/**
 * The wind at a point, computed purely from storm and tornado data. Common code with no Minecraft types, so
 * the server (commands, later entity forces) and the client (rain drift, later sound) get the same answer from
 * the same snapshots without any extra network traffic.
 *
 * Model (a gameplay model, not meteorology):
 *  - Storms: inside 1.2 x radius the wind blows in the storm's direction of travel, plus a smaller pull toward
 *    the centre (inflow). Strength falls off with the square of distance from the centre and scales with the
 *    storm's intensity. Peak is 30 mph for a 120-block storm up to 60 mph for a 350-block supercell.
 *  - Tornadoes: counter-clockwise rotation (seen from above) plus inflow. Speed rises linearly to the tornado's
 *    peak windMph at its radius, then falls off as 1/r out to 5 x radius.
 *  - Gusts: a smooth, deterministic variation of +/-25% over time and position.
 *
 * Coordinates: +x east, +z south (Minecraft). Wind components are the direction the air moves TOWARD, in mph.
 */
public final class WindField {
    private static final double INFLOW_FRACTION = 0.5;
    private static final double TORNADO_INFLOW_FRACTION = 0.4;

    private WindField() {
    }

    public record Wind(double x, double z, double gust) {
        /** Steady speed in mph, without gusts. */
        public double speedMph() {
            return Math.sqrt(this.x * this.x + this.z * this.z);
        }

        /** Speed in mph including the current gust factor. */
        public double gustSpeedMph() {
            return speedMph() * this.gust;
        }

        /** Compass bearing in degrees (0 = north, 90 = east) the wind blows FROM. */
        public double fromBearingDegrees() {
            // Air moves toward (x east, z south). It comes from the opposite side.
            double east = -this.x;   // moving toward +x (east) means coming from the west
            double north = this.z;   // moving toward +z (south) means coming from the north
            double deg = Math.toDegrees(Math.atan2(east, north));
            return (deg % 360.0 + 360.0) % 360.0;
        }

        /** 16-point compass name of the direction the wind blows from, e.g. "WSW". */
        public String fromCompass() {
            String[] names = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
            int index = (int) Math.round(fromBearingDegrees() / 22.5) % 16;
            return names[index];
        }
    }

    /**
     * @param elapsedTicks ticks since the snapshots were taken (positions are extrapolated by velocity)
     * @param gameTime     current game time in ticks, drives the gust variation
     */
    public static Wind sample(List<StormSnapshot> storms, List<TornadoSnapshot> tornadoes,
                              double elapsedTicks, double gameTime, double px, double pz) {
        double wx = 0.0;
        double wz = 0.0;

        for (StormSnapshot s : storms) {
            double cx = s.x() + s.vx() * elapsedTicks;
            double cz = s.z() + s.vz() * elapsedTicks;
            double dx = px - cx;
            double dz = pz - cz;
            double d = Math.sqrt(dx * dx + dz * dz);
            double reach = s.radius() * 1.2;
            if (d >= reach) {
                continue;
            }
            double falloff = 1.0 - d / reach;
            double f = falloff * falloff * s.intensity();
            double sizeFactor = Math.max(0.0, Math.min(1.0, (s.radius() - 120.0) / 230.0));
            double peak = 30.0 + 30.0 * sizeFactor;

            double speed = Math.sqrt((double) s.vx() * s.vx() + (double) s.vz() * s.vz());
            if (speed > 1.0E-9) {
                wx += s.vx() / speed * peak * f;
                wz += s.vz() / speed * peak * f;
            }
            if (d > 1.0) {
                wx += -dx / d * peak * INFLOW_FRACTION * f;
                wz += -dz / d * peak * INFLOW_FRACTION * f;
            }
        }

        for (TornadoSnapshot t : tornadoes) {
            double cx = t.x() + t.vx() * elapsedTicks;
            double cz = t.z() + t.vz() * elapsedTicks;
            double dx = px - cx;
            double dz = pz - cz;
            double d = Math.sqrt(dx * dx + dz * dz);
            double size = Math.max(1.0, t.size());
            if (d >= size * 5.0 || d < 1.0E-6) {
                continue;
            }
            double profile = d <= size ? d / size : size / d;
            double speed = t.windMph() * t.intensity() * profile;
            // Counter-clockwise seen from above in (east, south) coordinates is (dz, -dx) / d.
            double tx = dz / d;
            double tz = -dx / d;
            wx += tx * speed;
            wz += tz * speed;
            wx += -dx / d * speed * TORNADO_INFLOW_FRACTION;
            wz += -dz / d * speed * TORNADO_INFLOW_FRACTION;
        }

        double gust = 1.0 + 0.25 * Math.sin(gameTime * 0.05 + px * 0.013) * Math.sin(gameTime * 0.031 + pz * 0.017 + 1.0);
        return new Wind(wx, wz, gust);
    }
}
