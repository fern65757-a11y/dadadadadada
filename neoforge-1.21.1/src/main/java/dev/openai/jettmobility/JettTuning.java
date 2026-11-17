package dev.openai.jettmobility;

/** Constantes de jogabilidade compartilhadas entre servidor e cliente (a previsão do cliente usa os mesmos valores). */
public final class JettTuning {
    private JettTuning() {}

    /** Velocidade vertical do Updraft (blocos/tick). */
    public static final double UPDRAFT_VELOCITY = 1.05;

    /** Tailwind: velocidade constante por alguns ticks (2.4 blocos/tick x 5 ticks ~ 12 blocos), depois freada suave. */
    public static final int DASH_TICKS = 5;
    public static final double DASH_SPEED = 2.4;
    public static final double DASH_END_SPEED = 0.35;

    /** Cloudburst: esfera menor (raio em blocos) que é ARREMESSADA e só abre ao pousar. */
    public static final double SPHERE_RADIUS = 2.0;
    public static final int SPHERE_TICKS = 150;
    public static final double CLOUD_THROW_SPEED = 1.4; // blocos/tick
    public static final double CLOUD_THROW_RANGE = 24.0;

    /** Ultimate (Tempestade de Facas): modo com 5 facas arremessáveis. */
    public static final int STORM_TICKS = 40; // animação de ativação (anel de facas + funil de vento)
    public static final int KNIVES_MAX = 5;
    public static final int KNIFE_MODE_TICKS = 500; // 25 s; cada abate reinicia
    public static final int KNIFE_THROW_COOLDOWN = 5; // ticks entre arremessos
    public static final double KNIFE_RANGE = 80.0;
    public static final double KNIFE_SPEED = 3.5; // blocos/tick (só afeta a animação; o acerto é instantâneo)
    public static final int BODY_HITS_TO_KILL = 3;
}
