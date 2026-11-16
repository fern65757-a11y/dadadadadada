package dev.openai.jettmobility;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lógica de servidor (autoritativa) das habilidades: desbloqueio permanente, cooldowns, movimento, efeitos
 * e o modo Tempestade de Facas (5 facas arremessáveis com dano).
 * Os visuais ricos (espirais, fumaça) são tocados no cliente por um pacote FxS2C enviado a todos os jogadores próximos;
 * a esfera, o anel e as facas voando são entidades 3D reais (item_display), visíveis a todos.
 */
public final class JettAbilities {
    private JettAbilities() {}

    /** Máscara de bits dos desbloqueios. Persistente no save do jogador e mantida após a morte. */
    public static final AttachmentType<Integer> UNLOCKS = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(JettMobility.MOD_ID, "unlocks"),
            builder -> builder.persistent(Codec.INT).copyOnDeath());

    // Sons vanilla referenciados pelo id (evita depender dos nomes das constantes em SoundEvents).
    private static final SoundEvent SND_WIND_BURST = sound("entity.wind_charge.wind_burst");
    private static final SoundEvent SND_WIND_THROW = sound("entity.wind_charge.throw");
    private static final SoundEvent SND_BREEZE_JUMP = sound("entity.breeze.jump");
    private static final SoundEvent SND_BREEZE_IDLE = sound("entity.breeze.idle_air");
    private static final SoundEvent SND_BREEZE_SLIDE = sound("entity.breeze.slide");
    private static final SoundEvent SND_BREEZE_LAND = sound("entity.breeze.land");
    private static final SoundEvent SND_FLAP = sound("entity.ender_dragon.flap");
    private static final SoundEvent SND_BEACON = sound("block.beacon.activate");
    private static final SoundEvent SND_KNIFE_THROW = sound("entity.player.attack.sweep");
    private static final SoundEvent SND_HIT = sound("entity.arrow.hit_player");
    private static final SoundEvent SND_HEADSHOT = sound("entity.player.attack.crit");
    private static final SoundEvent SND_KILL = sound("entity.player.levelup");

    private static final Map<UUID, long[]> READY_AT = new HashMap<>();
    private static final Map<UUID, Dash> DASHES = new HashMap<>();
    private static final Map<UUID, KnifeMode> KNIFE_MODES = new HashMap<>();
    private static final List<Effect> EFFECTS = new ArrayList<>();
    /** Efeitos criados enquanto EFFECTS está sendo percorrido entram aqui e são movidos depois. */
    private static final List<Effect> PENDING = new ArrayList<>();
    private static long now = 0;

    private static SoundEvent sound(String path) {
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("minecraft", path));
    }

    public static void init() {
        FxDisplays.init();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            now++;
            tickDashes(server);
            tickKnifeModes(server);
            EFFECTS.removeIf(effect -> !effect.tick(server));
            EFFECTS.addAll(PENDING);
            PENDING.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            EFFECTS.clear();
            PENDING.clear();
            DASHES.clear();
            KNIFE_MODES.clear();
            READY_AT.clear();
            now = 0;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sync(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            READY_AT.remove(handler.player.getUUID());
            DASHES.remove(handler.player.getUUID());
            KNIFE_MODES.remove(handler.player.getUUID());
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> sync(newPlayer));
    }

    // ------------------------------------------------------------------ desbloqueio

    public static int mask(ServerPlayer player) {
        return player.getAttachedOrElse(UNLOCKS, 0);
    }

    public static boolean isUnlocked(ServerPlayer player, Ability ability) {
        return (mask(player) & ability.bit()) != 0;
    }

    public static void unlock(ServerPlayer player, Ability ability) {
        player.setAttached(UNLOCKS, mask(player) | ability.bit());
        sync(player);
    }

    // ------------------------------------------------------------------ ativação

    public static void tryActivate(ServerPlayer player, int abilityId, float dirX, float dirZ) {
        Ability ability = Ability.byId(abilityId);
        if (ability == null || player.isSpectator() || !player.isAlive()) return;
        if (!isUnlocked(player, ability)) {
            sync(player);
            return;
        }
        long[] ready = READY_AT.computeIfAbsent(player.getUUID(), id -> new long[Ability.VALUES.length]);
        if (ready[ability.ordinal()] > now) return; // ainda em cooldown (o servidor decide, nunca o cliente)
        ready[ability.ordinal()] = now + ability.cooldownTicks;

        ServerLevel level = (ServerLevel) player.level();
        switch (ability) {
            case UPDRAFT -> updraft(player, level);
            case CLOUDBURST -> cloudburst(player, level);
            case TAILWIND -> tailwind(player, level, dirX, dirZ);
            case ULTIMATE -> ultimate(player, level);
        }
        sync(player);
    }

    private static void updraft(ServerPlayer p, ServerLevel level) {
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(v.x, JettTuning.UPDRAFT_VELOCITY, v.z);
        p.hurtMarked = true; // força o envio da nova velocidade ao cliente do jogador
        p.fallDistance = 0;
        play(level, p.getX(), p.getY(), p.getZ(), SND_WIND_BURST, 1.0f, 1.1f);
        play(level, p.getX(), p.getY(), p.getZ(), SND_BREEZE_JUMP, 0.8f, 1.2f);
        fxFollowing(p, JettNetworking.FX_UPDRAFT, 0, 0, 0, 18);
    }

    private static void tailwind(ServerPlayer p, ServerLevel level, float dirX, float dirZ) {
        // Direção pedida pelo cliente (teclas de movimento); se vier inválida, usa para onde o jogador olha.
        double dx = dirX, dz = dirZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (!Double.isFinite(len) || len < 0.1 || len > 1.5) {
            double yaw = Math.toRadians(p.getYRot());
            dx = -Math.sin(yaw);
            dz = Math.cos(yaw);
            len = 1.0;
        }
        dx /= len;
        dz /= len;
        Dash dash = new Dash(dx, dz);
        DASHES.put(p.getUUID(), dash);
        applyDash(p, dash); // primeiro tick já neste instante
        play(level, p.getX(), p.getY(), p.getZ(), SND_FLAP, 1.0f, 1.7f);
        play(level, p.getX(), p.getY(), p.getZ(), SND_BREEZE_SLIDE, 0.9f, 1.3f);
        play(level, p.getX(), p.getY(), p.getZ(), SND_WIND_BURST, 0.7f, 1.6f);
        fxFollowing(p, JettNetworking.FX_DASH, dx, 0, dz, 16);
    }

    /** Cloudburst: a bola é ARREMESSADA (arco + rastro) e só abre a esfera de fumaça ao pousar no chão. */
    private static void cloudburst(ServerPlayer p, ServerLevel level) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(JettTuning.CLOUD_THROW_RANGE)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 target = hit.getLocation();
        if (hit.getType() != HitResult.Type.MISS) target = target.subtract(look.scale(0.3));
        Vec3 from = target.add(0, 0.6, 0);
        BlockHitResult down = level.clip(new ClipContext(from, from.add(0, -24.0, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 ground = down.getType() == HitResult.Type.MISS ? target : down.getLocation();

        int flight = (int) Math.max(5, Math.min(30, Math.ceil(eye.distanceTo(ground) / JettTuning.CLOUD_THROW_SPEED)));
        play(level, p.getX(), p.getY(), p.getZ(), SND_WIND_THROW, 1.0f, 1.0f);
        play(level, p.getX(), p.getY(), p.getZ(), SND_WIND_BURST, 0.6f, 1.6f);
        EFFECTS.add(new CloudThrowEffect(level, ground, flight));
        fxFollowing(p, JettNetworking.FX_CLOUD_THROW, ground.x, ground.y, ground.z, flight);
    }

    /** Ultimate: ativa o modo Tempestade de Facas (5 facas) + animação de ativação. */
    private static void ultimate(ServerPlayer p, ServerLevel level) {
        KNIFE_MODES.put(p.getUUID(), new KnifeMode());
        play(level, p.getX(), p.getY(), p.getZ(), SND_WIND_BURST, 1.3f, 0.7f);
        play(level, p.getX(), p.getY(), p.getZ(), SND_BEACON, 0.9f, 1.4f);
        MinecraftServer server = level.getServer();
        UUID display = FxDisplays.spawn(server, level, p.position().add(0, 0.95, 0), "jettmobility:fx/knife_ring", 0.05);
        EFFECTS.add(new KnifeRingEffect(level, p.getUUID(), display));
        fxFollowing(p, JettNetworking.FX_STORM, 0, 0, 0, JettTuning.STORM_TICKS);
    }

    // ------------------------------------------------------------------ dash em vários ticks

    private static final class Dash {
        final double dx, dz;
        int left = JettTuning.DASH_TICKS;

        Dash(double dx, double dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    private static void applyDash(ServerPlayer p, Dash d) {
        double speed = d.left > 1 ? JettTuning.DASH_SPEED : JettTuning.DASH_END_SPEED;
        p.setDeltaMovement(d.dx * speed, 0.0, d.dz * speed);
        p.hurtMarked = true;
        p.fallDistance = 0;
        d.left--;
    }

    private static void tickDashes(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Dash>> it = DASHES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Dash> entry = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            Dash d = entry.getValue();
            if (p == null || !p.isAlive() || d.left <= 0) {
                it.remove();
                continue;
            }
            applyDash(p, d);
            if (d.left <= 0) it.remove();
        }
    }

    // ------------------------------------------------------------------ Tempestade de Facas

    private static final class KnifeMode {
        int knives = JettTuning.KNIVES_MAX;
        int ticksLeft = JettTuning.KNIFE_MODE_TICKS;
        int cooldown = 0;
    }

    private static void tickKnifeModes(MinecraftServer server) {
        Iterator<Map.Entry<UUID, KnifeMode>> it = KNIFE_MODES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, KnifeMode> entry = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            KnifeMode mode = entry.getValue();
            if (mode.cooldown > 0) mode.cooldown--;
            if (p == null || !p.isAlive() || --mode.ticksLeft <= 0) {
                it.remove();
                if (p != null) sync(p);
            }
        }
    }

    /**
     * Arremesso de uma faca (clique de atirar). O acerto é instantâneo (raio de até 80 blocos, parando em blocos);
     * a faca 3D voa até o ponto de impacto só como animação.
     * Cabeça = morte instantânea. Corpo = 1/3 da vida máxima (3 facas matam), ignorando armadura.
     * Matar recupera as 5 facas.
     */
    public static void tryThrowKnife(ServerPlayer p) {
        KnifeMode mode = KNIFE_MODES.get(p.getUUID());
        if (mode == null || mode.knives <= 0 || mode.cooldown > 0 || !p.isAlive() || p.isSpectator()) return;
        mode.knives--;
        mode.cooldown = JettTuning.KNIFE_THROW_COOLDOWN;

        ServerLevel level = (ServerLevel) p.level();
        MinecraftServer server = level.getServer();
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        Vec3 end = eye.add(look.scale(JettTuning.KNIFE_RANGE));
        BlockHitResult blockHit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 stop = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();

        LivingEntity target = null;
        Vec3 targetPoint = null;
        double best = Double.MAX_VALUE;
        AABB search = new AABB(eye, stop).inflate(1.0);
        for (Entity e : level.getEntities(p, search, ent -> ent instanceof LivingEntity le && le.isAlive() && !le.isSpectator())) {
            Optional<Vec3> point = e.getBoundingBox().inflate(0.1).clip(eye, stop);
            if (point.isEmpty()) continue;
            double d = eye.distanceToSqr(point.get());
            if (d < best) {
                best = d;
                target = (LivingEntity) e;
                targetPoint = point.get();
            }
        }

        Vec3 impact = target != null ? targetPoint : stop;
        boolean killed = false;
        if (target != null) {
            boolean head = targetPoint.y >= target.getEyeY() - 0.25;
            float damage = head ? 1000.0f
                    : (float) Math.ceil(target.getMaxHealth() / (double) JettTuning.BODY_HITS_TO_KILL);
            DamageSource source = level.damageSources().indirectMagic(p, p); // ignora armadura; crédito do abate é do jogador
            target.invulnerableTime = 0; // sem isso, a 2ª e 3ª faca seriam ignoradas pelo tempo de invulnerabilidade
            target.hurtServer(level, source, damage);
            killed = target.isDeadOrDying();
            play(level, impact.x, impact.y, impact.z, head ? SND_HEADSHOT : SND_HIT, 1.0f, head ? 1.3f : 1.0f);
        }
        if (killed) {
            mode.knives = JettTuning.KNIVES_MAX;
            mode.ticksLeft = JettTuning.KNIFE_MODE_TICKS;
            play(level, p.getX(), p.getY(), p.getZ(), SND_KILL, 0.8f, 1.5f);
        }

        // animação: faca 3D sai da mão e desliza até o ponto de impacto, fica cravada e some
        Vec3 start = eye.add(look.scale(0.5)).add(0, -0.25, 0);
        Vec3 stuck = impact.subtract(look.scale(0.35));
        int ticks = (int) Math.max(1, Math.min(40, Math.ceil(start.distanceTo(stuck) / JettTuning.KNIFE_SPEED)));
        // quaternion do caminho mais curto que leva o eixo +Y (lâmina do modelo) até a direção do voo
        double cx = look.z, cy = 0.0, cz = -look.x, cw = 1.0 + look.y;
        double norm = Math.sqrt(cx * cx + cy * cy + cz * cz + cw * cw);
        if (norm < 1e-6) { cx = 1; cy = 0; cz = 0; cw = 0; norm = 1; }
        UUID display = FxDisplays.spawn(server, level, start, "jettmobility:wind_knife", 0.5,
                cx / norm, cy / norm, cz / norm, cw / norm, ticks);
        if (display != null) {
            FxDisplays.teleport(server, level, display, stuck.x, stuck.y, stuck.z);
            EFFECTS.add(new TimedKillEffect(level, display, ticks + 30));
        }
        play(level, p.getX(), p.getY(), p.getZ(), SND_KNIFE_THROW, 0.9f, 1.5f);
        fxFollowing(p, JettNetworking.FX_KNIFE, impact.x, impact.y, impact.z, ticks);
        sync(p);
    }

    // ------------------------------------------------------------------ sincronização

    public static void sync(ServerPlayer p) {
        if (!ServerPlayNetworking.canSend(p, JettNetworking.StateS2C.TYPE)) return;
        long[] ready = READY_AT.get(p.getUUID());
        int[] cd = new int[4];
        for (int i = 0; i < cd.length; i++) {
            long remaining = ready == null ? 0 : ready[i] - now;
            cd[i] = (int) Math.max(0, Math.min(remaining, Integer.MAX_VALUE));
        }
        KnifeMode mode = KNIFE_MODES.get(p.getUUID());
        int knives = mode == null ? 0 : mode.knives;
        ServerPlayNetworking.send(p, new JettNetworking.StateS2C(mask(p), cd[0], cd[1], cd[2], cd[3], knives));
    }

    /** Efeito que acompanha um jogador: vai para ele e para quem o está vendo. */
    private static void fxFollowing(ServerPlayer origin, int kind, double x, double y, double z, int duration) {
        JettNetworking.FxS2C packet = new JettNetworking.FxS2C(kind, origin.getId(), x, y, z, duration);
        sendFx(origin, packet);
        for (ServerPlayer viewer : PlayerLookup.tracking(origin)) {
            if (viewer != origin) sendFx(viewer, packet);
        }
    }

    private static void fxAround(ServerLevel level, Vec3 pos, JettNetworking.FxS2C packet) {
        for (ServerPlayer viewer : PlayerLookup.around(level, pos, 128.0)) {
            sendFx(viewer, packet);
        }
    }

    private static void sendFx(ServerPlayer to, JettNetworking.FxS2C packet) {
        if (ServerPlayNetworking.canSend(to, JettNetworking.FxS2C.TYPE)) {
            ServerPlayNetworking.send(to, packet);
        }
    }

    private static void play(ServerLevel level, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, x, y, z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    // ------------------------------------------------------------------ efeitos com duração

    private interface Effect {
        /** @return false quando o efeito terminou e deve ser removido. */
        boolean tick(MinecraftServer server);
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Bola da Cloudburst em voo; ao pousar, abre a esfera de fumaça. */
    private static final class CloudThrowEffect implements Effect {
        private final ServerLevel level;
        private final Vec3 ground;
        private final int flight;
        private int age = 0;

        CloudThrowEffect(ServerLevel level, Vec3 ground, int flight) {
            this.level = level;
            this.ground = ground;
            this.flight = flight;
        }

        @Override
        public boolean tick(MinecraftServer server) {
            if (age++ < flight) return true;
            Vec3 center = ground.add(0, JettTuning.SPHERE_RADIUS * 0.75, 0);
            UUID display = FxDisplays.spawn(server, level, center, "jettmobility:fx/smoke_core", 0.05);
            play(level, center.x, center.y, center.z, SND_WIND_BURST, 1.3f, 0.8f);
            play(level, center.x, center.y, center.z, SND_BREEZE_LAND, 1.1f, 0.7f);
            PENDING.add(new SphereEffect(level, center, display));
            fxAround(level, center, new JettNetworking.FxS2C(JettNetworking.FX_SPHERE, -1,
                    center.x, center.y, center.z, JettTuning.SPHERE_TICKS));
            return false;
        }
    }

    /** Esfera de fumaça: o corpo 3D cresce, gira e encolhe até sumir; as partículas giratórias vêm do cliente. */
    private static final class SphereEffect implements Effect {
        private static final double FULL_SCALE = JettTuning.SPHERE_RADIUS / 0.75; // modelo tem raio 0.75 bloco
        private final ServerLevel level;
        private final Vec3 center;
        private final UUID display;
        private int age = 0;

        SphereEffect(ServerLevel level, Vec3 center, UUID display) {
            this.level = level;
            this.center = center;
            this.display = display;
        }

        @Override
        public boolean tick(MinecraftServer server) {
            if (age >= JettTuning.SPHERE_TICKS) {
                FxDisplays.kill(level, display);
                play(level, center.x, center.y, center.z, SND_BREEZE_LAND, 0.8f, 0.9f);
                return false;
            }
            if (display != null && age % 4 == 0) {
                int t = age + 4;
                double grow = smooth(t / 10.0);
                double fade = smooth((JettTuning.SPHERE_TICKS - t) / 45.0);
                double scale = Math.max(0.02, FULL_SCALE * grow * fade);
                FxDisplays.animate(server, level, display, 0.35, 1.0, 0.15, t * 0.05, scale, 4);
            }
            if (age % 30 == 0) play(level, center.x, center.y, center.z, SND_BREEZE_IDLE, 1.1f, 0.8f);
            age++;
            return true;
        }
    }

    /** Anel de facas 3D girando em volta do jogador na ativação da ultimate. */
    private static final class KnifeRingEffect implements Effect {
        private static final double FULL_SCALE = 2.3; // raio do anel ~1.65 bloco
        private final ServerLevel level;
        private final UUID owner;
        private final UUID display;
        private int age = 0;

        KnifeRingEffect(ServerLevel level, UUID owner, UUID display) {
            this.level = level;
            this.owner = owner;
            this.display = display;
        }

        @Override
        public boolean tick(MinecraftServer server) {
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            boolean over = p == null || !p.isAlive() || p.level() != level || age >= JettTuning.STORM_TICKS || display == null;
            if (over) {
                FxDisplays.kill(level, display);
                return false;
            }
            FxDisplays.teleport(server, level, display, p.getX(), p.getY() + 0.95, p.getZ());
            if (age % 4 == 0) {
                int t = age + 4;
                double grow = smooth(t / 8.0);
                double fade = smooth((JettTuning.STORM_TICKS - t) / 15.0);
                double scale = Math.max(0.02, FULL_SCALE * grow * fade);
                FxDisplays.animate(server, level, display, 0, 1, 0, t * 0.28, scale, 4);
            }
            if (age % 12 == 0) play(level, p.getX(), p.getY(), p.getZ(), SND_BREEZE_IDLE, 1.0f, 1.3f);
            age++;
            return true;
        }
    }

    /** Remove uma entidade de efeito depois de alguns ticks (faca cravada). */
    private static final class TimedKillEffect implements Effect {
        private final ServerLevel level;
        private final UUID display;
        private int left;

        TimedKillEffect(ServerLevel level, UUID display, int ticks) {
            this.level = level;
            this.display = display;
            this.left = ticks;
        }

        @Override
        public boolean tick(MinecraftServer server) {
            if (--left > 0) return true;
            FxDisplays.kill(level, display);
            return false;
        }
    }
}
