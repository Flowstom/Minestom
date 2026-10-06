package net.minestom.server.registry;

import net.minestom.server.codec.Transcoder;
import net.minestom.server.codec.TranscoderProxy;

import java.util.Objects;

/**
 * A codec transcoder carrying registry ownership and encoding options.
 *
 * @param transcoder the underlying transcoder
 * @param registries the registries used by codecs
 * @param forClient whether unsupported registry serializers are filtered for vanilla clients
 * @param itemStackView whether item codecs participate in the scoped client view or creative import
 * @param <D> the encoded value type
 */
public record RegistryTranscoder<D>(
        Transcoder<D> transcoder,
        Registries registries,
        boolean forClient,
        boolean itemStackView
) implements TranscoderProxy<D> {

    public RegistryTranscoder(Transcoder<D> transcoder, Registries registries) {
        this(Objects.requireNonNull(transcoder), registries, false, false);
    }

    public RegistryTranscoder(Transcoder<D> transcoder, Registries registries, boolean forClient) {
        this(transcoder, registries, forClient, false);
    }

    @Override
    public Transcoder<D> delegate() {
        return transcoder;
    }

}
