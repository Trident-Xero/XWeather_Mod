package com.tridentxero.xweathermod.sound;

import com.tridentxero.xweathermod.XWeatherMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Sound events. Common code (the registry must exist on the server too). The audio files are
 * assets/xweathermod/sounds/*.ogg and are described in assets/xweathermod/sounds.json.
 * The clips are synthesised placeholders (filtered noise): replace the .ogg files with your own recordings
 * of the same names whenever you like.
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, XWeatherMod.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> TORNADO_ROAR = register("tornado_roar");
    public static final DeferredHolder<SoundEvent, SoundEvent> TORNADO_HUM = register("tornado_hum");
    public static final DeferredHolder<SoundEvent, SoundEvent> WIND_GUST = register("wind_gust");

    private ModSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name,
                () -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(XWeatherMod.MOD_ID, name)));
    }
}
