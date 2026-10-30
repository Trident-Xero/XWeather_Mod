package com.tridentxero.xweathermod.client;

import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.sound.ModSounds;
import com.tridentxero.xweathermod.storm.ClientStormState;
import com.tridentxero.xweathermod.storm.TornadoSnapshot;
import com.tridentxero.xweathermod.storm.WindField;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client only. Plays weather sounds around the local player from the synced storm data.
 *
 *  - Rain: vanilla's rain sounds, only while {@link RainSpawner#rainLevel()} is above zero. Outdoors it uses the
 *    normal rain loop, under a roof the quieter indoor variant.
 *  - Wind: a gust clip every 3-5 seconds outdoors while the wind is above 15 mph, louder with speed.
 *  - Tornado roar: the nearest tornado within 600 blocks, every 8 seconds (the clip is 9 s, so they overlap into a
 *    continuous growl), louder when closer and stronger, lower pitched for bigger tornadoes.
 *  - Tornado hum: any tornado with a peak wind ABOVE 201 mph within 600 blocks adds a rough, choir-like hum (centred near 200 Hz) on top of
 *    the roar, every 7.5 seconds (clip is 8 s). It does not depend on how far into its life the tornado is. It is played a few blocks from you in the tornado's direction, so it pans
 *    toward it (it is not a true positional sound).
 *
 * Clips are short one-shot sounds started on a timer, not looping sound instances: simple and cheap.
 *
 * UNVERIFIED against a compile on 26.1.2: ClientLevel#playLocalSound(double, double, double, SoundEvent,
 * SoundSource, float, float, boolean), ClientLevel#canSeeSky, SoundEvents.WEATHER_RAIN and WEATHER_RAIN_ABOVE,
 * SoundSource.WEATHER.
 */
@EventBusSubscriber(modid = XWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class SoundSpawner {
    private static final double STALE_TICKS = 100.0;
    private static final double ROAR_RANGE = 600.0;
    /** Peak wind above which a tornado hums (mph). */
    private static final double HUM_MIN_MPH = 201.0;

    private static ClientLevel lastLevel = null;
    private static int rainTimer = 0;
    private static int windTimer = 0;
    private static int roarTimer = 0;
    private static int humTimer = 0;

    private SoundSpawner() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != lastLevel) {
            lastLevel = level;
            rainTimer = 0;
            windTimer = 40;
            roarTimer = 0;
            humTimer = 0;
        }
        if (level == null || player == null || mc.isPaused() || !ClientConfig.SOUND_ENABLED.get()) {
            return;
        }
        float volume = ClientConfig.SOUND_VOLUME.get().floatValue();
        if (volume <= 0.0F) {
            return;
        }
        RandomSource random = level.getRandom();
        boolean outdoors = level.canSeeSky(player.blockPosition());
        double elapsed = (System.nanoTime() - ClientStormState.lastUpdateNanos()) / 50_000_000.0;
        boolean fresh = elapsed <= STALE_TICKS;

        // --- Rain ---
        float rain = RainSpawner.rainLevel();
        if (rain > 0.05F && --rainTimer <= 0) {
            rainTimer = 12;
            float v = (0.1F + 0.3F * rain) * volume * (outdoors ? 1.0F : 0.5F);
            level.playLocalSound(player.getX(), player.getY(), player.getZ(),
                    outdoors ? SoundEvents.WEATHER_RAIN_ABOVE : SoundEvents.WEATHER_RAIN,
                    SoundSource.WEATHER, v, 0.9F + random.nextFloat() * 0.2F, false);
        }

        if (!fresh) {
            return;
        }

        // --- Wind gusts ---
        if (--windTimer <= 0) {
            windTimer = 60 + random.nextInt(40);
            WindField.Wind wind = WindField.sample(ClientStormState.storms(), ClientStormState.tornadoes(),
                    elapsed, level.getGameTime(), player.getX(), player.getZ());
            double mph = wind.gustSpeedMph();
            if (mph >= 15.0 && outdoors) {
                float v = (float) Math.min(1.0, mph / 70.0) * 0.9F * volume;
                level.playLocalSound(player.getX(), player.getY(), player.getZ(), ModSounds.WIND_GUST.get(),
                        SoundSource.WEATHER, v, 0.85F + random.nextFloat() * 0.3F, false);
            }
        }

        // --- Tornado roar ---
        if (--roarTimer <= 0) {
            roarTimer = 160; // clip is 9 s with 1 s soft ends, so consecutive plays blend with no pulsing
            TornadoSnapshot nearest = null;
            double best = ROAR_RANGE;
            double bx = 0.0;
            double bz = 0.0;
            for (TornadoSnapshot t : ClientStormState.tornadoes()) {
                double cx = t.x() + t.vx() * elapsed;
                double cz = t.z() + t.vz() * elapsed;
                double d = Math.sqrt((cx - player.getX()) * (cx - player.getX())
                        + (cz - player.getZ()) * (cz - player.getZ()));
                if (d < best) {
                    best = d;
                    nearest = t;
                    bx = cx - player.getX();
                    bz = cz - player.getZ();
                }
            }
            if (nearest != null && nearest.intensity() > 0.05F) {
                double closeness = 1.0 - best / ROAR_RANGE;
                float v = (float) (Math.pow(closeness, 1.3) * (0.4 + 0.6 * nearest.intensity())) * 2.0F * volume
                        * (outdoors ? 1.0F : 0.65F);
                // Low and heavy: 0.55 for the biggest tornadoes up to 0.95 for small ones.
                float pitch = (float) Math.max(0.55, Math.min(0.95, 0.95 - nearest.size() / 350.0));
                double len = Math.max(1.0E-6, best);
                level.playLocalSound(player.getX() + bx / len * 8.0, player.getY(), player.getZ() + bz / len * 8.0,
                        ModSounds.TORNADO_ROAR.get(), SoundSource.WEATHER, Math.min(2.0F, v), pitch, false);
            }
        }

        // --- Tornado hum (peak wind above 201 mph) ---
        if (--humTimer <= 0) {
            humTimer = 140; // clip is 8 s with 1 s soft ends
            TornadoSnapshot loudest = null;
            double bestDist = ROAR_RANGE;
            double hx = 0.0;
            double hz = 0.0;
            for (TornadoSnapshot t : ClientStormState.tornadoes()) {
                if (t.windMph() <= HUM_MIN_MPH) {
                    continue;
                }
                double cx = t.x() + t.vx() * elapsed;
                double cz = t.z() + t.vz() * elapsed;
                double d = Math.sqrt((cx - player.getX()) * (cx - player.getX())
                        + (cz - player.getZ()) * (cz - player.getZ()));
                if (d < bestDist) {
                    bestDist = d;
                    loudest = t;
                    hx = cx - player.getX();
                    hz = cz - player.getZ();
                }
            }
            if (loudest != null && loudest.intensity() > 0.05F) {
                double closeness = 1.0 - bestDist / ROAR_RANGE;
                float v = (float) (Math.pow(closeness, 1.1) * (0.4 + 0.6 * loudest.intensity())) * 1.5F * volume
                        * (outdoors ? 1.0F : 0.8F); // a hum carries through walls better than a roar
                // The clip is a rough, choir-like hum centred near 196 Hz; bigger tornadoes sound slightly lower.
                float pitch = (float) Math.max(0.92, Math.min(1.08, 1.08 - loudest.size() / 900.0));
                double len = Math.max(1.0E-6, bestDist);
                level.playLocalSound(player.getX() + hx / len * 8.0, player.getY(), player.getZ() + hz / len * 8.0,
                        ModSounds.TORNADO_HUM.get(), SoundSource.WEATHER, Math.min(2.0F, v), pitch, false);
            }
        }
    }
}
