package rtfstreamsconnector.rivers;

import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverWarp;
import rtfstreamsconnector.util.PrivateFieldReflector;

import java.lang.invoke.VarHandle;

public final class RiverWarpReflection {
    private static final VarHandle seed = PrivateFieldReflector.varHandle(RiverWarp.class, "seed", int.class);
    private static final VarHandle scale = PrivateFieldReflector.varHandle(RiverWarp.class, "scale", float.class);
    private static final VarHandle frequency = PrivateFieldReflector.varHandle(RiverWarp.class, "frequency", float.class);
    private static final VarHandle lower = PrivateFieldReflector.varHandle(RiverWarp.class, "lower", float.class);
    private static final VarHandle upper = PrivateFieldReflector.varHandle(RiverWarp.class, "upper", float.class);

    private RiverWarpReflection() {
    }

    public static int seedOf(RiverWarp riverWarp) {
        return (int) seed.get(riverWarp);
    }

    public static float scaleOf(RiverWarp riverWarp) {
        return (float) scale.get(riverWarp);
    }

    public static float frequencyOf(RiverWarp riverWarp) {
        return (float) frequency.get(riverWarp);
    }

    public static float lowerOf(RiverWarp riverWarp) {
        return (float) lower.get(riverWarp);
    }

    public static float upperOf(RiverWarp riverWarp) {
        return (float) upper.get(riverWarp);
    }
}
