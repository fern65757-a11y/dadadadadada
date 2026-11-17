package dev.openai.jettmobility;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Item craftável que, ao ser usado UMA vez (botão direito), desbloqueia a habilidade de forma permanente.
 * Depois do desbloqueio, a habilidade é usada só pela tecla: não é preciso segurar nada.
 */
public class UnlockItem extends Item {
    private final Ability ability;

    public UnlockItem(Properties properties, Ability ability) {
        super(properties);
        this.ability = ability;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }
        if (JettAbilities.isUnlocked(serverPlayer, ability)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.jettmobility.already_unlocked", Component.translatable(ability.nameKey())), true);
            return InteractionResultHolder.fail(stack);
        }
        JettAbilities.unlock(serverPlayer, ability);
        stack.consume(1, player);
        serverPlayer.displayClientMessage(
                Component.translatable("message.jettmobility.unlocked", Component.translatable(ability.nameKey())), true);
        return InteractionResultHolder.success(stack);
    }
}
