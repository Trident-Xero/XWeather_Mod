package com.tridentxero.xweathermod.storm;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tridentxero.xweathermod.XWeatherMod;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Per-dimension persistent storm and tornado lists.
 *
 * UNVERIFIED against a compile: the SavedDataType constructor shape (id, supplier, codec) is taken from the
 * NeoForge 26.1 docs, which also mention an optional fourth DataFixTypes argument (null for mods).
 * If the compiler asks for four arguments, add {@code , null}.
 */
public class StormSavedData extends SavedData {
    public static final Codec<StormSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StormCell.CODEC.listOf().fieldOf("storms").forGetter(d -> d.storms),
            TornadoCell.CODEC.listOf().fieldOf("tornadoes").forGetter(d -> d.tornadoes),
            Codec.LONG.fieldOf("lastTick").forGetter(d -> d.lastTick),
            Codec.INT.fieldOf("nextId").forGetter(d -> d.nextId)
    ).apply(instance, StormSavedData::new));

    // Lambda (not a constructor reference) so overload resolution cannot become ambiguous
    // with the NeoForge Factory(ServerLevel) overload.
    public static final SavedDataType<StormSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(XWeatherMod.MOD_ID, "storms"),
            () -> new StormSavedData(),
            CODEC);

    private final List<StormCell> storms;
    private final List<TornadoCell> tornadoes;
    /** Game time of the last simulation step; -1 when never simulated. */
    private long lastTick;
    private int nextId;

    public StormSavedData() {
        this(new ArrayList<>(), new ArrayList<>(), -1L, 1);
    }

    private StormSavedData(List<StormCell> storms, List<TornadoCell> tornadoes, long lastTick, int nextId) {
        this.storms = new ArrayList<>(storms);
        this.tornadoes = new ArrayList<>(tornadoes);
        this.lastTick = lastTick;
        this.nextId = nextId;
    }

    public List<StormCell> storms() {
        return this.storms;
    }

    public List<TornadoCell> tornadoes() {
        return this.tornadoes;
    }

    public long lastTick() {
        return this.lastTick;
    }

    public void setLastTick(long lastTick) {
        this.lastTick = lastTick;
    }

    public int allocateId() {
        return this.nextId++;
    }
}
