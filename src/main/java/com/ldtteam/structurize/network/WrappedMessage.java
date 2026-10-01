package com.ldtteam.structurize.network;

import com.ldtteam.structurize.api.util.constant.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Transport frame used to migrate Structurize's indexed messages onto
 * NeoForge payloads without changing each message's buffer format.
 * One payload type carries every message; the message id travels inside the frame.
 */
public record WrappedMessage(int messageId, byte[] data) implements CustomPacketPayload
{
    public static final Type<WrappedMessage> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "message"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WrappedMessage> CODEC = CustomPacketPayload.codec(
        WrappedMessage::write,
        WrappedMessage::read);

    private static WrappedMessage read(final FriendlyByteBuf buf)
    {
        return new WrappedMessage(buf.readVarInt(), buf.readByteArray());
    }

    private void write(final FriendlyByteBuf buf)
    {
        buf.writeVarInt(messageId);
        buf.writeByteArray(data);
    }

    @Override
    public Type<?> type()
    {
        return TYPE;
    }
}
