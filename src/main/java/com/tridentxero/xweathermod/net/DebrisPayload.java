package com.tridentxero.xweathermod.net;

import java.util.ArrayList;
import java.util.List;

import com.tridentxero.xweathermod.XWeatherMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: blocks a tornado has just removed. Visual only; the blocks are already gone on the server.
 * Each piece carries its position, its block state id (Block.getId) and whether it was lifted into the tornado
 * (it then orbits the funnel) or just destroyed (it bursts into fragments).
 */
public record DebrisPayload(int tornadoId, List<Piece> pieces) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DebrisPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(XWeatherMod.MOD_ID, "debris"));

    public static final StreamCodec<ByteBuf, DebrisPayload> STREAM_CODEC =
            StreamCodec.of(DebrisPayload::write, DebrisPayload::read);

    /** Largest piece list sent in one packet. */
    public static final int MAX_PIECES = 200;

    public record Piece(float x, float y, float z, int stateId, boolean lifted) {
    }

    private static void write(ByteBuf buf, DebrisPayload payload) {
        ByteBufCodecs.VAR_INT.encode(buf, payload.tornadoId);
        ByteBufCodecs.VAR_INT.encode(buf, payload.pieces.size());
        for (Piece p : payload.pieces) {
            buf.writeFloat(p.x());
            buf.writeFloat(p.y());
            buf.writeFloat(p.z());
            ByteBufCodecs.VAR_INT.encode(buf, p.stateId());
            buf.writeBoolean(p.lifted());
        }
    }

    private static DebrisPayload read(ByteBuf buf) {
        int id = ByteBufCodecs.VAR_INT.decode(buf);
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        if (count < 0 || count > MAX_PIECES) {
            throw new IllegalArgumentException("Invalid debris count: " + count);
        }
        List<Piece> pieces = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float x = buf.readFloat();
            float y = buf.readFloat();
            float z = buf.readFloat();
            int state = ByteBufCodecs.VAR_INT.decode(buf);
            boolean lifted = buf.readBoolean();
            pieces.add(new Piece(x, y, z, state, lifted));
        }
        return new DebrisPayload(id, pieces);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
