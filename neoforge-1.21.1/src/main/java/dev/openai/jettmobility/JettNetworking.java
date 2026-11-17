package dev.openai.jettmobility;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public final class JettNetworking {
    private JettNetworking() {}

    public static final int FX_UPDRAFT = 0;
    public static final int FX_DASH = 1;
    public static final int FX_SPHERE = 2;
    public static final int FX_STORM = 3;
    public static final int FX_KNIFE = 4;
    public static final int FX_CLOUD_THROW = 5;

    /** Cliente -> servidor: "apertei a tecla da habilidade N". (dirX, dirZ) = direção de movimento desejada (Tailwind). */
    public record AbilityC2S(int ability, float dirX, float dirZ) implements CustomPacketPayload {
        public static final Type<AbilityC2S> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(JettMobility.MOD_ID, "ability"));
        public static final StreamCodec<RegistryFriendlyByteBuf, AbilityC2S> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AbilityC2S::ability,
                ByteBufCodecs.FLOAT, AbilityC2S::dirX,
                ByteBufCodecs.FLOAT, AbilityC2S::dirZ,
                AbilityC2S::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Cliente -> servidor: "cliquei para arremessar uma faca" (só vale com o modo Tempestade de Facas ativo). */
    public record KnifeThrowC2S() implements CustomPacketPayload {
        public static final Type<KnifeThrowC2S> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(JettMobility.MOD_ID, "throw"));
        public static final StreamCodec<RegistryFriendlyByteBuf, KnifeThrowC2S> CODEC =
                StreamCodec.unit(new KnifeThrowC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Servidor -> cliente dono: desbloqueios, cooldowns (ticks) e facas restantes, para o HUD e para o clique. */
    public record StateS2C(int mask, int cd0, int cd1, int cd2, int cd3, int knives) implements CustomPacketPayload {
        public static final Type<StateS2C> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(JettMobility.MOD_ID, "state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StateS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, StateS2C::mask,
                ByteBufCodecs.VAR_INT, StateS2C::cd0,
                ByteBufCodecs.VAR_INT, StateS2C::cd1,
                ByteBufCodecs.VAR_INT, StateS2C::cd2,
                ByteBufCodecs.VAR_INT, StateS2C::cd3,
                ByteBufCodecs.VAR_INT, StateS2C::knives,
                StateS2C::new);

        public int cooldown(int index) {
            return switch (index) { case 0 -> cd0; case 1 -> cd1; case 2 -> cd2; default -> cd3; };
        }

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Servidor -> todos os jogadores próximos: "toque o efeito visual X".
     * Para efeitos que acompanham alguém, entityId é o jogador e (x,y,z) carrega a direção ou o ponto final.
     */
    public record FxS2C(int kind, int entityId, double x, double y, double z, int duration) implements CustomPacketPayload {
        public static final Type<FxS2C> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(JettMobility.MOD_ID, "fx"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FxS2C> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FxS2C::kind,
                ByteBufCodecs.VAR_INT, FxS2C::entityId,
                ByteBufCodecs.DOUBLE, FxS2C::x,
                ByteBufCodecs.DOUBLE, FxS2C::y,
                ByteBufCodecs.DOUBLE, FxS2C::z,
                ByteBufCodecs.VAR_INT, FxS2C::duration,
                FxS2C::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(AbilityC2S.TYPE, AbilityC2S.CODEC,
                (payload, context) -> JettAbilities.tryActivate((ServerPlayer) context.player(), payload.ability(), payload.dirX(), payload.dirZ()));
        registrar.playToServer(KnifeThrowC2S.TYPE, KnifeThrowC2S.CODEC,
                (payload, context) -> JettAbilities.tryThrowKnife((ServerPlayer) context.player()));
        registrar.playToClient(StateS2C.TYPE, StateS2C.CODEC, ClientPayloadHandlers::state);
        registrar.playToClient(FxS2C.TYPE, FxS2C.CODEC, ClientPayloadHandlers::fx);
    }
}
