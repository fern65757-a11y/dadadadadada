package dev.openai.jettmobility;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.PlayerCloudParticle;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

@Mod(value = JettMobility.MOD_ID, dist = Dist.CLIENT)
public class JettMobilityClient {
    private static final String CATEGORY = "key.category.jettmobility.main";
    private static final KeyMapping[] KEYS = new KeyMapping[Ability.VALUES.length];

    // Previsão local do dash: o cliente já começa a se mover no mesmo tick da tecla (sem esperar a ida e volta ao servidor).
    private static int dashTicksLeft = 0;
    private static double dashDx = 0, dashDz = 0;

    public static KeyMapping keyFor(Ability ability) {
        return KEYS[ability.ordinal()];
    }

    public JettMobilityClient(IEventBus modBus) {
        modBus.addListener(JettMobilityClient::registerKeys);
        modBus.addListener(JettMobilityClient::registerParticles);
        modBus.addListener(JettMobilityClient::registerHud);
        NeoForge.EVENT_BUS.addListener(JettMobilityClient::onTickPre);
        NeoForge.EVENT_BUS.addListener(JettMobilityClient::onTickPost);
        NeoForge.EVENT_BUS.addListener(JettMobilityClient::onLogout);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        KEYS[Ability.UPDRAFT.ordinal()] = key(Ability.UPDRAFT, InputConstants.KEY_Q);
        KEYS[Ability.CLOUDBURST.ordinal()] = key(Ability.CLOUDBURST, InputConstants.KEY_E);
        KEYS[Ability.TAILWIND.ordinal()] = key(Ability.TAILWIND, InputConstants.KEY_C);
        KEYS[Ability.ULTIMATE.ordinal()] = key(Ability.ULTIMATE, InputConstants.KEY_X);
        for (KeyMapping k : KEYS) event.register(k);
    }

    private static void registerParticles(RegisterParticleProvidersEvent event) {
        // partículas próprias: textura/descrição em assets, comportamento de nuvem do vanilla (ajustado por JettFx)
        event.registerSpriteSet(JettParticles.SMOKE_PUFF.get(), PlayerCloudParticle.Provider::new);
        event.registerSpriteSet(JettParticles.WIND_WISP.get(), PlayerCloudParticle.Provider::new);
    }

    private static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(JettMobility.MOD_ID, "abilities"), new JettHud());
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        JettClientState.reset();
        JettFx.clear();
        dashTicksLeft = 0;
    }

    // Antes do tratamento de teclas do jogo: quando uma habilidade desbloqueada usa a mesma tecla de uma ação
    // vanilla (E = inventário, Q = soltar item), consumimos o clique do vanilla e só a habilidade é acionada.
    private static void onTickPre(ClientTickEvent.Pre event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        if (!client.isPaused()) JettClientState.tick();
        applyDashPrediction(client);
        if (client.screen != null) return;
        if (JettClientState.knives() > 0) { // modo Tempestade de Facas: clique esquerdo arremessa a faca
            while (client.options.keyAttack.consumeClick()) {
                PacketDistributor.sendToServer(new JettNetworking.KnifeThrowC2S());
                client.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        for (Ability ability : Ability.VALUES) {
            KeyMapping key = KEYS[ability.ordinal()];
            while (key.consumeClick()) {
                if (!JettClientState.isUnlocked(ability)) continue; // sem desbloqueio: a tecla segue vanilla
                activate(client, ability);
                swallowVanillaConflicts(client, key);
            }
        }
    }

    private static void onTickPost(ClientTickEvent.Post event) {
        JettFx.tick(Minecraft.getInstance());
    }

    private static void activate(Minecraft client, Ability ability) {
        double[] dir = moveDirection(client);
        PacketDistributor.sendToServer(new JettNetworking.AbilityC2S(ability.id(), (float) dir[0], (float) dir[1]));
        if (!JettClientState.isReady(ability) || client.player.isSpectator()) return;
        // previsão local (o servidor continua sendo a autoridade e corrige o estado)
        switch (ability) {
            case UPDRAFT -> {
                Vec3 v = client.player.getDeltaMovement();
                client.player.setDeltaMovement(v.x, JettTuning.UPDRAFT_VELOCITY, v.z);
                JettClientState.startCooldown(ability);
            }
            case TAILWIND -> {
                dashDx = dir[0];
                dashDz = dir[1];
                dashTicksLeft = JettTuning.DASH_TICKS;
                applyDashPrediction(client);
                JettClientState.startCooldown(ability);
            }
            default -> JettClientState.startCooldown(ability);
        }
    }

    private static void applyDashPrediction(Minecraft client) {
        if (dashTicksLeft <= 0 || client.player == null) return;
        double speed = dashTicksLeft > 1 ? JettTuning.DASH_SPEED : JettTuning.DASH_END_SPEED;
        client.player.setDeltaMovement(dashDx * speed, 0.0, dashDz * speed);
        dashTicksLeft--;
    }

    /** Direção horizontal desejada: teclas de movimento relativas ao olhar; sem teclas, para a frente. */
    private static double[] moveDirection(Minecraft client) {
        double yaw = Math.toRadians(client.player.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double lx = Math.cos(yaw), lz = Math.sin(yaw);
        int forward = (client.options.keyUp.isDown() ? 1 : 0) - (client.options.keyDown.isDown() ? 1 : 0);
        int strafe = (client.options.keyLeft.isDown() ? 1 : 0) - (client.options.keyRight.isDown() ? 1 : 0);
        if (forward == 0 && strafe == 0) forward = 1;
        double dx = fx * forward + lx * strafe;
        double dz = fz * forward + lz * strafe;
        double len = Math.sqrt(dx * dx + dz * dz);
        return new double[] {dx / len, dz / len};
    }

    private static KeyMapping key(Ability ability, int code) {
        return new KeyMapping("key." + JettMobility.MOD_ID + "." + ability.key, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    private static void swallowVanillaConflicts(Minecraft client, KeyMapping jett) {
        InputConstants.Key bound = jett.getKey();
        for (KeyMapping other : client.options.keyMappings) {
            if (other == jett || isJettKey(other)) continue;
            if (bound.equals(other.getKey())) {
                while (other.consumeClick()) {
                    // descarta o clique da ação vanilla que divide a tecla
                }
            }
        }
    }

    private static boolean isJettKey(KeyMapping candidate) {
        for (KeyMapping key : KEYS) {
            if (key == candidate) return true;
        }
        return false;
    }
}
