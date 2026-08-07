package net.minestom.server.network.packet.server;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.player.PlayerConnection;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a packet that can be sent to a {@link PlayerConnection}.
 */
public sealed interface SendablePacket
        permits BufferedPacket, CachedPacket, FramedPacket, ServerPacket {

    static @Nullable ServerPacket extractServerPacket(ConnectionState state, SendablePacket packet) {
        return switch (packet) {
            case ServerPacket serverPacket -> serverPacket;
            case CachedPacket cachedPacket -> cachedPacket.packet(state);
            case FramedPacket framedPacket -> framedPacket.packet();
            case BufferedPacket bufferedPacket -> null;
        };
    }

    /**
     * Returns whether a pre-serialized representation may depend on player context.
     */
    static boolean isContextSensitive(SendablePacket packet) {
        return switch (packet) {
            case ServerPacket _ -> true;
            case CachedPacket cachedPacket -> cachedPacket.isContextSensitive();
            case FramedPacket _ -> true;
            case BufferedPacket _ -> false;
        };
    }
}
