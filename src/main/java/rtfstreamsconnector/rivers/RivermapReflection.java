package rtfstreamsconnector.rivers;

import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.river.Network;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import rtfstreamsconnector.util.PrivateFieldReflector;

import java.lang.invoke.VarHandle;

public final class RivermapReflection {
    private static final VarHandle networks = PrivateFieldReflector.varHandle(Rivermap.class, "networks", Network[].class);
    private static final VarHandle riverWarp = PrivateFieldReflector.varHandle(Rivermap.class, "riverWarp", Domain.class);

    private RivermapReflection() {}

    public static Network[] networksOf(Rivermap rivermap) {
        return (Network[]) networks.get(rivermap);
    }

    public static Domain riverWarpOf(Rivermap rivermap) {
        return (Domain) riverWarp.get(rivermap);
    }
}
