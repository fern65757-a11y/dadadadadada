package dev.openai.jettmobility;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Entidades item_display do vanilla usadas como "objetos 3D" visíveis a todos (multiplayer): a esfera de fumaça e o anel de facas.
 * São criadas/animadas com comandos do próprio jogo (summon / data merge / tp), porque a transformação e a interpolação
 * dessas entidades são controladas por NBT. O modelo vem de itens registrados só para isso (assets/jettmobility/models/item/fx_*.json).
 */
public final class FxDisplays {
    private FxDisplays() {}

    private static final Logger LOG = LoggerFactory.getLogger("jettmobility");
    private static final String TAG = "jettmobility_fx";
    private static final Set<UUID> LIVE = new HashSet<>();

    public static void init(IEventBus gameBus) {
        // Se o servidor caiu no meio de um efeito, entidades antigas ficam salvas no mundo: removemos ao carregar.
        gameBus.addListener((EntityJoinLevelEvent event) -> {
            Entity entity = event.getEntity();
            if (!event.getLevel().isClientSide() && entity.getTags().contains(TAG) && !LIVE.contains(entity.getUUID())) {
                event.setCanceled(true);
            }
        });
        gameBus.addListener((ServerStoppingEvent event) -> {
            for (UUID id : new HashSet<>(LIVE)) {
                for (ServerLevel level : event.getServer().getAllLevels()) {
                    Entity e = level.getEntity(id);
                    if (e != null) e.discard();
                }
            }
            LIVE.clear();
        });
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String ff(double v) {
        return f(v) + "f";
    }

    private static boolean run(MinecraftServer server, ServerLevel level, String command) {
        CommandSourceStack source = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
        try {
            server.getCommands().getDispatcher().execute(command, source);
            return true;
        } catch (CommandSyntaxException e) {
            LOG.warn("[jettmobility] comando de efeito falhou: {} -> {}", command, e.getMessage());
            return false;
        }
    }

    /** Cria um item_display com o modelo indicado (item registrado com esse id, ex.: "jettmobility:fx_smoke_core"). Devolve o UUID. */
    public static UUID spawn(MinecraftServer server, ServerLevel level, Vec3 pos, String itemModel, double scale) {
        return spawn(server, level, pos, itemModel, scale, 0, 0, 0, 1, 3);
    }

    /**
     * Versão completa: rotação inicial (quaternion x,y,z,w) e duração da interpolação de movimento (ticks, 0..59):
     * um único tp depois do summon faz o cliente deslizar a entidade suavemente até o destino.
     */
    public static UUID spawn(MinecraftServer server, ServerLevel level, Vec3 pos, String itemModel, double scale,
                             double qx, double qy, double qz, double qw, int teleportTicks) {
        UUID id = UUID.randomUUID();
        LIVE.add(id); // registrar ANTES: o evento de carga não pode tratar esta entidade como "órfã"
        long msb = id.getMostSignificantBits();
        long lsb = id.getLeastSignificantBits();
        String s = ff(scale);
        String command = "summon minecraft:item_display " + f(pos.x) + " " + f(pos.y) + " " + f(pos.z)
                + " {UUID:[I;" + (int) (msb >> 32) + "," + (int) msb + "," + (int) (lsb >> 32) + "," + (int) lsb + "]"
                + ",Tags:[\"" + TAG + "\"]"
                + ",billboard:\"fixed\",view_range:2.0f,teleport_duration:" + Math.max(0, Math.min(59, teleportTicks))
                + ",interpolation_duration:4,start_interpolation:-1"
                + ",item_display:\"none\",brightness:{sky:15,block:15}"
                + ",item:{id:\"" + itemModel + "\",count:1}"
                + ",transformation:{left_rotation:[" + ff(qx) + "," + ff(qy) + "," + ff(qz) + "," + ff(qw)
                + "],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:["
                + s + "," + s + "," + s + "]}}";
        if (!run(server, level, command)) {
            LIVE.remove(id);
            return null;
        }
        return id;
    }

    /** Anima a entidade até a nova rotação (eixo + ângulo em radianos) e escala ao longo de 'ticks' ticks. */
    public static void animate(MinecraftServer server, ServerLevel level, UUID id,
                               double ax, double ay, double az, double angle, double scale, int ticks) {
        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-6) { ax = 0; ay = 1; az = 0; len = 1; }
        double sin = Math.sin(angle / 2) / len;
        double cos = Math.cos(angle / 2);
        String s = ff(scale);
        run(server, level, "data merge entity " + id + " {start_interpolation:0,interpolation_duration:" + ticks
                + ",transformation:{left_rotation:[" + ff(ax * sin) + "," + ff(ay * sin) + "," + ff(az * sin) + "," + ff(cos)
                + "],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[" + s + "," + s + "," + s + "]}}");
    }

    public static void teleport(MinecraftServer server, ServerLevel level, UUID id, double x, double y, double z) {
        run(server, level, "tp " + id + " " + f(x) + " " + f(y) + " " + f(z));
    }

    public static void kill(ServerLevel level, UUID id) {
        if (id == null) return;
        Entity e = level.getEntity(id);
        if (e != null) e.discard();
        LIVE.remove(id);
    }
}
