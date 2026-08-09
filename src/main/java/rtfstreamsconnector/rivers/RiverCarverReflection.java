package rtfstreamsconnector.rivers;

import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Range;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.RiverCarver;
import rtfstreamsconnector.util.PrivateFieldReflector;

import java.lang.invoke.VarHandle;

public final class RiverCarverReflection {
    private static final VarHandle fade = PrivateFieldReflector.varHandle(RiverCarver.class, "fade", float.class);
    private static final VarHandle bedWidth = PrivateFieldReflector.varHandle(RiverCarver.class, "bedWidth", Range.class);
    private static final VarHandle banksWidth = PrivateFieldReflector.varHandle(RiverCarver.class, "banksWidth", Range.class);
    private static final VarHandle valleyWidth = PrivateFieldReflector.varHandle(RiverCarver.class, "valleyWidth", Range.class);

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
}
