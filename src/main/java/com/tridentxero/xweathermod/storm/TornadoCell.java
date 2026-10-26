package com.tridentxero.xweathermod.storm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Server-side tornado state (data only in this build: no visuals, wind forces or damage yet).
 * windMph is the peak wind speed requested; size is the radius in blocks.
 */
public final class TornadoCell {
    public static final Codec<TornadoCell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("id").forGetter(c -> c.id),
            Codec.DOUBLE.fieldOf("x").forGetter(c -> c.x),
            Codec.DOUBLE.fieldOf("z").forGetter(c -> c.z),
            Codec.FLOAT.fieldOf("vx").forGetter(c -> c.vx),
            Codec.FLOAT.fieldOf("vz").forGetter(c -> c.vz),
            Codec.FLOAT.fieldOf("windMph").forGetter(c -> c.windMph),
            Codec.FLOAT.fieldOf("size").forGetter(c -> c.size),
            Codec.INT.fieldOf("age").forGetter(c -> c.age),
            Codec.INT.fieldOf("maxAge").forGetter(c -> c.maxAge)
    ).apply(instance, TornadoCell::new));

    public final int id;
    public double x;
    public double z;
    /** Blocks per tick. */
    public float vx;
    public float vz;
    public final float windMph;
    public final float size;
    public int age;
    public final int maxAge;

    public TornadoCell(int id, double x, double z, float vx, float vz, float windMph, float size, int age, int maxAge) {
        this.id = id;
        this.x = x;
        this.z = z;
        this.vx = vx;
        this.vz = vz;
        this.windMph = windMph;
        this.size = size;
        this.age = age;
        this.maxAge = Math.max(1, maxAge);
    }

    /** 0..1: ramps up over the first 10% of life, holds, ramps down over the last 10%. */
    public float intensity() {
        float edge = Math.max(1.0F, this.maxAge * 0.1F);
        float rise = Math.min(1.0F, this.age / edge);
        float fall = Math.min(1.0F, (this.maxAge - this.age) / edge);
        return Math.max(0.0F, Math.min(rise, fall));
    }

    public boolean expired() {
        return this.age >= this.maxAge;
    }

    public TornadoTier tier() {
        return TornadoTier.fromWind(this.windMph);
    }

    public TornadoSnapshot snapshot() {
        return new TornadoSnapshot(this.id, (float) this.x, (float) this.z, this.vx, this.vz,
                this.windMph, this.size, intensity());
    }
}
