package com.tridentxero.xweathermod.net;

import java.util.ArrayList;
import java.util.List;

import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.storm.StormSnapshot;
import com.tridentxero.xweathermod.storm.TornadoSnapshot;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: the storms and tornadoes in the receiving player's current dimension. Compact state only,
 * no particles. Pattern follows the NeoForge 26.1 payload docs.
 *
 * UNVERIFIED against a compile: StreamCodec.of(encoder, decoder) argument order is (buf, value).
 */
public record StormSyncPayload(List<StormSnapshot> storms, List<TornadoSnapshot> tornadoes)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StormSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(XWeatherMod.MOD_ID, "storm_sync"));

    public static final StreamCodec<ByteBuf, StormSyncPayload> STREAM_CODEC =
            StreamCodec.of(StormSyncPayload::write, StormSyncPayload::read);

    private static final int MAX_ENTRIES = 256;

    private static void write(ByteBuf buf, StormSyncPayload payload) {
        ByteBufCodecs.VAR_INT.encode(buf, payload.storms.size());
        for (StormSnapshot s : payload.storms) {
            ByteBufCodecs.VAR_INT.encode(buf, s.id());
            buf.writeFloat(s.x());
            buf.writeFloat(s.z());
            buf.writeFloat(s.vx());
            buf.writeFloat(s.vz());
            buf.writeFloat(s.radius());
            buf.writeFloat(s.intensity());
            buf.writeBoolean(s.supercell());
            buf.writeFloat(s.development());
        }
        ByteBufCodecs.VAR_INT.encode(buf, payload.tornadoes.size());
        for (TornadoSnapshot t : payload.tornadoes) {
            ByteBufCodecs.VAR_INT.encode(buf, t.id());
            buf.writeFloat(t.x());
            buf.writeFloat(t.z());
            buf.writeFloat(t.vx());
            buf.writeFloat(t.vz());
            buf.writeFloat(t.windMph());
            buf.writeFloat(t.size());
            buf.writeFloat(t.intensity());
        }
    }

    private static StormSyncPayload read(ByteBuf buf) {
        int stormCount = ByteBufCodecs.VAR_INT.decode(buf);
        // Bound the lists so a bad packet cannot allocate unbounded memory.
        if (stormCount < 0 || stormCount > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid storm count: " + stormCount);
        }
        List<StormSnapshot> storms = new ArrayList<>(stormCount);
        for (int i = 0; i < stormCount; i++) {
            int id = ByteBufCodecs.VAR_INT.decode(buf);
            float x = buf.readFloat();
            float z = buf.readFloat();
            float vx = buf.readFloat();
            float vz = buf.readFloat();
            float radius = buf.readFloat();
            float intensity = buf.readFloat();
            boolean supercell = buf.readBoolean();
            float development = buf.readFloat();
            storms.add(new StormSnapshot(id, x, z, vx, vz, radius, intensity, supercell, development));
        }
        int tornadoCount = ByteBufCodecs.VAR_INT.decode(buf);
        if (tornadoCount < 0 || tornadoCount > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid tornado count: " + tornadoCount);
        }
        List<TornadoSnapshot> tornadoes = new ArrayList<>(tornadoCount);
        for (int i = 0; i < tornadoCount; i++) {
            int id = ByteBufCodecs.VAR_INT.decode(buf);
            float x = buf.readFloat();
            float z = buf.readFloat();
            float vx = buf.readFloat();
            float vz = buf.readFloat();
            float wind = buf.readFloat();
            float size = buf.readFloat();
            float intensity = buf.readFloat();
            tornadoes.add(new TornadoSnapshot(id, x, z, vx, vz, wind, size, intensity));
        }
        return new StormSyncPayload(storms, tornadoes);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
