package dev.openai.jettmobility;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

@Mod(JettMobility.MOD_ID)
public class JettMobility {
    public static final String MOD_ID = "jettmobility";

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MOD_ID);

    public static final Supplier<Item> UPDRAFT_UNLOCK = unlock(Ability.UPDRAFT);
    public static final Supplier<Item> CLOUDBURST_UNLOCK = unlock(Ability.CLOUDBURST);
    public static final Supplier<Item> TAILWIND_UNLOCK = unlock(Ability.TAILWIND);
    public static final Supplier<Item> ULTIMATE_UNLOCK = unlock(Ability.ULTIMATE);

    /** Faca de vento: item de coleção com modelo 3D próprio (mão e inventário). Não causa dano. */
    public static final Supplier<Item> WIND_KNIFE = ITEMS.register("wind_knife", () -> new Item(new Item.Properties().stacksTo(1)));

    // Itens "invisíveis" (fora do criativo): só servem de modelo 3D para as entidades item_display dos efeitos.
    public static final Supplier<Item> FX_SMOKE_CORE = ITEMS.register("fx_smoke_core", () -> new Item(new Item.Properties()));
    public static final Supplier<Item> FX_KNIFE_RING = ITEMS.register("fx_knife_ring", () -> new Item(new Item.Properties()));

    private static Supplier<Item> unlock(Ability ability) {
        return ITEMS.register(ability.unlockItem, () -> new UnlockItem(new Item.Properties().stacksTo(16), ability));
    }

    public JettMobility(IEventBus modBus) {
        ITEMS.register(modBus);
        JettParticles.PARTICLES.register(modBus);
        JettAbilities.ATTACHMENTS.register(modBus);
        modBus.addListener(JettNetworking::register);
        modBus.addListener(this::addCreative);
        JettAbilities.init(NeoForge.EVENT_BUS);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(UPDRAFT_UNLOCK.get());
            event.accept(CLOUDBURST_UNLOCK.get());
            event.accept(TAILWIND_UNLOCK.get());
            event.accept(ULTIMATE_UNLOCK.get());
            event.accept(WIND_KNIFE.get());
        }
    }
}
