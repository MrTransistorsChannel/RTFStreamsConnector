package rtfstreamsconnector.rivers;

import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Range;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarver;
import rtfstreamsconnector.util.PrivateFieldReflector;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

public final class RiverCarverReflection {
    private static final VarHandle fade = PrivateFieldReflector.varHandle(RiverCarver.class, "fade", float.class);
    private static final VarHandle bedWidth = PrivateFieldReflector.varHandle(RiverCarver.class, "bedWidth", Range.class);
    private static final VarHandle banksWidth = PrivateFieldReflector.varHandle(RiverCarver.class, "banksWidth", Range.class);
    private static final VarHandle valleyWidth = PrivateFieldReflector.varHandle(RiverCarver.class, "valleyWidth", Range.class);
    private static final MethodHandle scaledSize = PrivateFieldReflector.methodHandle(RiverCarver.class, "getScaledSize", MethodType.methodType(float.class,
        float.class, Range.class));

    private RiverCarverReflection() {}

    public static float fadeOf(RiverCarver carver) {
        return (float) fade.get(carver);
    }

    public static Range bedWidth(RiverCarver carver) {
        return (Range) bedWidth.get(carver);
    }

    public static Range banksWidth(RiverCarver carver) {
        return (Range) banksWidth.get(carver);
    }

    public static Range valleyWidth(RiverCarver carver) {
        return (Range) valleyWidth.get(carver);
    }

    // Returns (half-width)² of the bank/bed/valley zone at section t
    public static float scaledSizeOf(RiverCarver carver, float t, Range range) {
        return PrivateFieldReflector.invoke(scaledSize, carver, t, range);
    }
}
