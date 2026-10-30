package com.tridentxero.xweathermod.storm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Server-side state of one storm. Mutable, persisted via {@link #CODEC}.
 * Intensity ramps up over the first 15% of life, holds at full strength, and fades over the last 20%.
 */
public final class StormCell {
    public static final Codec<StormCell> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("id").forGetter(c -> c.id),
            Codec.DOUBLE.fieldOf("x").forGetter(c -> c.x),
            Codec.DOUBLE.fieldOf("z").forGetter(c -> c.z),
            Codec.FLOAT.fieldOf("vx").forGetter(c -> c.vx),
            Codec.FLOAT.fieldOf("vz").forGetter(c -> c.vz),
            Codec.FLOAT.fieldOf("radius").forGetter(c -> c.radius),
            Codec.INT.fieldOf("age").forGetter(c -> c.age),
            Codec.INT.fieldOf("maxAge").forGetter(c -> c.maxAge),
            Codec.BOOL.optionalFieldOf("supercell", false).forGetter(c -> c.supercell),
            Codec.INT.optionalFieldOf("tornadoAtAge", -1).forGetter(c -> c.tornadoAtAge),
            Codec.FLOAT.optionalFieldOf("tornadoWind", 0.0F).forGetter(c -> c.tornadoWind),
            Codec.FLOAT.optionalFieldOf("tornadoSize", 0.0F).forGetter(c -> c.tornadoSize)
    ).apply(instance, StormCell::new));

    /** A supercell's tornado forms this fraction of its radius BEHIND its centre, under the wall cloud. */
    public static final double TORNADO_BACK_FRACTION = 0.25;
    /** Supercells reach full strength this fast (ticks) at most, so a 30-50 second build has a storm to show. */
    private static final float SUPERCELL_RISE_TICKS = 800.0F;

    public final int id;
    public double x;
    public double z;
    /** Blocks per tick. */
    public float vx;
    public float vz;
    public final float radius;
    public int age;
    public final int maxAge;
    /** True for supercells (spawned by command). Regular storms are false. */
    public final boolean supercell;
    /** Storm age at which this supercell drops a tornado, or -1 for none / already spawned. */
    public int tornadoAtAge;
    public final float tornadoWind;
    public final float tornadoSize;

    public StormCell(int id, double x, double z, float vx, float vz, float radius, int age, int maxAge) {
        this(id, x, z, vx, vz, radius, age, maxAge, false, -1, 0.0F, 0.0F);
    }

    public StormCell(int id, double x, double z, float vx, float vz, float radius, int age, int maxAge,
                     boolean supercell, int tornadoAtAge, float tornadoWind, float tornadoSize) {
        this.supercell = supercell;
        this.tornadoAtAge = tornadoAtAge;
        this.tornadoWind = tornadoWind;
        this.tornadoSize = tornadoSize;
        this.id = id;
        this.x = x;
        this.z = z;
        this.vx = vx;
        this.vz = vz;
        this.radius = radius;
        this.age = age;
        this.maxAge = Math.max(1, maxAge);
    }

    /** 0..1: quick rise, long plateau, slower fade. */
    public float intensity() {
        float progress = Math.min(1.0F, Math.max(0.0F, this.age / (float) this.maxAge));
        float rise = Math.min(1.0F, progress / 0.15F);
        if (this.supercell) {
            rise = Math.min(1.0F, this.age / Math.min(this.maxAge * 0.15F, SUPERCELL_RISE_TICKS));
        }
        float fall = Math.min(1.0F, (1.0F - progress) / 0.20F);
        return Math.max(0.0F, Math.min(rise, fall));
    }

    /** 0..1: how far a supercell has built toward dropping its tornado (1 once mature or when it has no timer). */
    public float development() {
        if (!this.supercell) {
            return 0.0F;
        }
        if (this.tornadoAtAge > 0) {
            return Math.max(0.0F, Math.min(1.0F, this.age / (float) this.tornadoAtAge));
        }
        return 1.0F;
    }

    public boolean expired() {
        return this.age >= this.maxAge;
    }

    public StormSnapshot snapshot() {
        return new StormSnapshot(this.id, (float) this.x, (float) this.z, this.vx, this.vz, this.radius, intensity(),
                this.supercell, development());
    }
}
