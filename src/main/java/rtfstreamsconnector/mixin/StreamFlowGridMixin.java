package rtfstreamsconnector.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.streamsreflowing.core.RiverEngine;
import dev.streamsreflowing.core.river.RiverFlowField;
import dev.streamsreflowing.flow.StreamFlowGrid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

// Streams boundary layer at river mouths, two parts:
// 1) In OCEAN-classified cells keep the flow pointing outward (the dilated skirt's copied
//    downstream direction) instead of letting the bank-follow blend (w) and the flare wrap
//    the mouth into a convergence point. Mid-river banks untouched.
// 2) In OCEAN-classified cells on the boundary of the flow blob (nonFlow > 0), rotate the
//    direction outward along the blob's normal so the plume fans into the sea instead of
//    running as a straight jet tangential to its own border.
// Anchors (Math.min, local slots 50/62/7/8/9/14/27/28/33/35/37/39/41, edgeDistance,
// encode(DD)B) are tied to the pinned Streams jar 2.10.7; they fail loudly on mismatch
// (defaultRequire: 1) - see DEV-NOTES.md.
@Mixin(StreamFlowGrid.class)
public class StreamFlowGridMixin {

    private static final String BUILD = "build(Ldev/streamsreflowing/core/RiverEngine;Ldev/streamsreflowing/core/river/RiverFlowField$BiomeClass;Ldev" +
        "/streamsreflowing/core/river/RiverFlowField$OrientationField;ZII)[B";
    private static final String W_MIN = "Ljava/lang/Math;min(DD)D";
    private static final String EDGE_DISTANCE = "Ldev/streamsreflowing/flow/StreamFlowGrid;edgeDistance([ZIIII)D";
    private static final String ENCODE = "Ldev/streamsreflowing/flow/StreamFlowGrid;encode(DD)B";

    // How strongly the plume fans out: 1.0 = direction + unit outward normal (45 deg at the flanks)
    private static final double FAN_SCALE = 1.0D;

    // Set per-cell in build()'s boundary-blend branch; read by the w/flare modifiers.
    // ThreadLocal because build() runs on worldgen threads.
    private static final ThreadLocal<Boolean> OCEAN_CELL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // Per-cell fan state, recomputed at the edgeDistance call (reached by every flow cell),
    // consumed by the encode redirect of the same cell. No cross-cell staleness.
    private static final ThreadLocal<Boolean> FAN_ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Double> FAN_X = ThreadLocal.withInitial(() -> 0.0D);
    private static final ThreadLocal<Double> FAN_Z = ThreadLocal.withInitial(() -> 0.0D);

    @Inject(method = BUILD, at = @At(value = "INVOKE", target = W_MIN, ordinal = 0), locals = LocalCapture.CAPTURE_FAILHARD)
    private static void rtfconnector$markOceanCell(RiverEngine engine, RiverFlowField.BiomeClass classifier, RiverFlowField.OrientationField orient,
                                                   boolean flowRivers, int cx, int cz, CallbackInfoReturnable<byte[]> cir, @Local(index = 8) int baseX,
                                                   @Local(index = 9) int baseZ, @Local(index = 27) int cellX, @Local(index = 28) int cellZ) {
        OCEAN_CELL.set(classifier.classify(baseX + cellX, baseZ + cellZ) == RiverFlowField.OCEAN);
    }

    // w - bank-follow blend weight: 0 in ocean cells so the skirt keeps its outward direction
    @ModifyVariable(method = BUILD, at = @At("STORE"), index = 50)
    private static double rtfconnector$killBankFollow(double w) {
        return OCEAN_CELL.get() ? 0.0D : w;
    }

    // flare - funnel push: 0 in ocean cells so the skirt does not funnel into the mouth tip
    @ModifyVariable(method = BUILD, at = @At("STORE"), index = 62)
    private static double rtfconnector$killFlare(double flare) {
        return OCEAN_CELL.get() ? 0.0D : flare;
    }

    // Runs right before edgeDistance, i.e. after the blend branch, for every flow cell.
    // For OCEAN cells with non-flow neighbors: fan the direction outward along the blob
    // normal (bnx/bnz), so the plume spreads instead of continuing as a straight jet.
    @Inject(method = BUILD, at = @At(value = "INVOKE", target = EDGE_DISTANCE), locals = LocalCapture.CAPTURE_FAILHARD)
    private static void rtfconnector$computeOceanFan(RiverEngine engine, RiverFlowField.BiomeClass classifier, RiverFlowField.OrientationField orient,
                                                     boolean flowRivers, int cx, int cz, CallbackInfoReturnable<byte[]> cir, @Local(index = 8) int baseX,
                                                     @Local(index = 9) int baseZ, @Local(index = 27) int cellX, @Local(index = 28) int cellZ, @Local(index =
            33) double dirX, @Local(index = 35) double dirZ, @Local(index = 37) double bnx, @Local(index = 39) double bnz, @Local(index = 41) int nonFlow) {
        FAN_ACTIVE.set(Boolean.FALSE);
        if (classifier.classify(baseX + cellX, baseZ + cellZ) != RiverFlowField.OCEAN) {
            return;
        }
        if (nonFlow <= 0) {
            return;
        }
        double nlen = Math.hypot(bnx, bnz);
        if (nlen <= 1.0E-6D) {
            return;
        }
        double fx = dirX + FAN_SCALE * bnx / nlen;
        double fz = dirZ + FAN_SCALE * bnz / nlen;
        double flen = Math.hypot(fx, fz);
        if (flen <= 1.0E-9D) {
            return;
        }
        FAN_X.set(fx / flen);
        FAN_Z.set(fz / flen);
        FAN_ACTIVE.set(Boolean.TRUE);
    }

    // The per-cell encode: for fanned ocean cells write the fanned direction instead.
    // (The second encode call in the final assembly loop is excluded via ordinal 0.)
    @Redirect(method = BUILD, at = @At(value = "INVOKE", target = ENCODE, ordinal = 0))
    private static byte rtfconnector$redirectEncode(double dirX, double dirZ) {
        if (FAN_ACTIVE.get()) {
            return StreamFlowGrid.encode(FAN_X.get(), FAN_Z.get());
        }
        return StreamFlowGrid.encode(dirX, dirZ);
    }
}