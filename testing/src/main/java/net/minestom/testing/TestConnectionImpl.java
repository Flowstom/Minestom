package net.minestom.testing;

import net.minestom.server.ServerFlag;
import net.minestom.server.ServerProcess;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStackView;
import net.minestom.server.item.ItemStackViewContext;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

final class TestConnectionImpl implements TestConnection {
    private final ServerProcess process;
    private final GameProfile gameProfile;
    private final PlayerConnectionImpl playerConnection = new PlayerConnectionImpl();

    private final AtomicBoolean connected = new AtomicBoolean(false);

    private final List<IncomingCollector<ServerPacket>> incomingTrackers = new CopyOnWriteArrayList<>();

    TestConnectionImpl(Env env, GameProfile gameProfile) {
        this.process = env.process();
        this.gameProfile = gameProfile;
    }

    @Override
    public Player connect(Instance instance, Pos pos) {
        if (!connected.compareAndSet(false, true)) {
            throw new IllegalStateException("Already connected");
        }

        var player = process.connection().createPlayer(playerConnection, gameProfile);
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(pos);
        });

        // Force the player through the entirety of the login process manually
        CompletableFuture<Player> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            // `isFirstConfig` is set to false in order to not block the thread
            // waiting for known packs.
            // The consequence is that registry packets cannot be listened to.
            process.connection().doConfiguration(player, false);
            process.connection().transitionConfigToPlay(player);
            future.complete(player);
        });
        future.join();
        playerConnection.setClientState(ConnectionState.PLAY);
        playerConnection.setServerState(ConnectionState.PLAY);
        process.connection().updateWaitingPlayers();
        return player;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ServerPacket> Collector<T> trackIncoming(Class<T> type) {
        var tracker = new IncomingCollector<>(type);
        this.incomingTrackers.add(IncomingCollector.class.cast(tracker));
        return tracker;
    }

    final class PlayerConnectionImpl extends PlayerConnection {
        private boolean online = true;

        @Override
        public void sendPacket(SendablePacket packet) {
            final var serverPacket = this.extractPacket(packet);
            for (var tracker : incomingTrackers) {
                if (tracker.type.isAssignableFrom(serverPacket.getClass())) tracker.packets.add(serverPacket);
            }
        }

        private ServerPacket extractPacket(final SendablePacket packet) {
            final Player player = getPlayer();
            final ItemStackView itemStackView = player != null ? player.getItemStackView() : ItemStackView.PASSTHROUGH;
            ServerPacket serverPacket;
            if (packet instanceof ServerPacket direct) {
                serverPacket = direct;
            } else {
                serverPacket = Objects.requireNonNull(SendablePacket.extractServerPacket(getServerState(), packet));
                if (itemStackView == ItemStackView.PASSTHROUGH) return serverPacket;
                if (!SendablePacket.isContextSensitive(packet)) return serverPacket;
            }
            if (player == null) return serverPacket;

            if (ServerFlag.AUTOMATIC_COMPONENT_TRANSLATION && serverPacket instanceof ServerPacket.ComponentHolding) {
                final var operator = ItemStackViewContext.componentOperator(player);
                if (itemStackView == ItemStackView.PASSTHROUGH) {
                    serverPacket = ((ServerPacket.ComponentHolding) serverPacket)
                            .copyWithOperator(Objects.requireNonNull(operator));
                } else {
                    final ServerPacket.ComponentHolding translatable = (ServerPacket.ComponentHolding) serverPacket;
                    serverPacket = ItemStackViewContext.suppressItemTranslation(() ->
                            translatable.copyWithOperator(Objects.requireNonNull(operator)));
                }
            }

            return itemStackView == ItemStackView.PASSTHROUGH ? serverPacket :
                    applyItemStackView(serverPacket, player, itemStackView);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private ServerPacket applyItemStackView(ServerPacket serverPacket, Player player, ItemStackView itemStackView) {
            final var registry = PacketVanilla.SERVER_PACKET_PARSER.stateRegistry(getServerState());
            final NetworkBuffer.Type serializer = registry.packetInfo(serverPacket.getClass()).serializer();
            final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(ServerFlag.POOLED_BUFFER_SIZE, process.registries());
            final boolean[] mapped = {false};
            ItemStackViewContext.withOutbound(itemStackView, player,
                    ItemStackViewContext.componentOperator(player),
                    () -> {
                        buffer.write(serializer, serverPacket);
                        mapped[0] = ItemStackViewContext.hasMappedItemStack();
                    });
            // Preserve the semantic packet object when its serializer contains no item stack.
            if (!mapped[0]) return serverPacket;
            buffer.readIndex(0);
            return (ServerPacket) buffer.read(serializer);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }

        @Override
        public boolean isOnline() {
            return online;
        }

        @Override
        public void disconnect() {
            online = false;
        }
    }

    final class IncomingCollector<T extends ServerPacket> implements Collector<T> {
        private final Class<T> type;
        private final List<T> packets = new CopyOnWriteArrayList<>();

        public IncomingCollector(Class<T> type) {
            this.type = type;
        }

        @Override
        public List<T> collect() {
            incomingTrackers.remove(this);
            return List.copyOf(packets);
        }
    }

    static final class TestPlayerImpl extends Player {
        public TestPlayerImpl(PlayerConnection playerConnection, GameProfile gameProfile) {
            super(playerConnection, gameProfile);
        }

        @Override
        public void sendChunk(Chunk chunk) {
            // Send immediately
            sendPacket(chunk.getFullDataPacket());
        }
    }
}
