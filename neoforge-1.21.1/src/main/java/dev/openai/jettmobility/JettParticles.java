package dev.openai.jettmobility;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Tipos de partícula próprios (texturas em assets/jettmobility/textures/particle e descrições em particles/). */
public final class JettParticles {
    private JettParticles() {}

    public static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, JettMobility.MOD_ID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SMOKE_PUFF =
            PARTICLES.register("smoke_puff", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WIND_WISP =
            PARTICLES.register("wind_wisp", () -> new SimpleParticleType(false));
}
