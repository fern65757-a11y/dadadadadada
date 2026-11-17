package dev.openai.jettmobility;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Receptores dos pacotes servidor -> cliente (separados para o servidor dedicado nunca carregar classes de cliente). */
public final class ClientPayloadHandlers {
    private ClientPayloadHandlers() {}

    public static void state(JettNetworking.StateS2C payload, IPayloadContext context) {
        JettClientState.apply(payload);
    }

    public static void fx(JettNetworking.FxS2C payload, IPayloadContext context) {
        JettFx.handle(payload);
    }
}
