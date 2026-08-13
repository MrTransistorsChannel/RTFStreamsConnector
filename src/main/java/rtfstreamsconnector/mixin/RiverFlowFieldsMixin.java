package rtfstreamsconnector.mixin;

import dev.streamsreflowing.core.river.RiverFlowField;
import dev.streamsreflowing.worldgen.RiverFlowFields;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rtfstreamsconnector.rivers.RTFFlowProvider;

@Mixin(RiverFlowFields.class)
public class RiverFlowFieldsMixin {

    @Inject(method = "classifierFor(Lnet/minecraft/server/level/ServerLevel;)" +
        "Ldev/streamsreflowing/core/river/RiverFlowField$BiomeClass;", at = @At("RETURN"), cancellable = true)
    private static void onClassifierFor(ServerLevel level, CallbackInfoReturnable<RiverFlowField.BiomeClass> cir) {
        RiverFlowField.BiomeClass biomeClass = cir.getReturnValue();
        if (biomeClass != null) {
            cir.setReturnValue(RTFFlowProvider.wrapClassifier(level, biomeClass));
        }
    }

    @Inject(method = "flowRadAt(Ldev/streamsreflowing/core/river/RiverFlowField$BiomeClass;" +
        "Ldev/streamsreflowing/core/river/RiverFlowField$OrientationField;II)D", at = @At("HEAD"), cancellable = true)
    private static void onFlowRadAt(RiverFlowField.BiomeClass biomeClass,
                                    RiverFlowField.OrientationField orientationField, int x, int z,
                                    CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(RTFFlowProvider.flowRadAt(biomeClass, x, z));
        cir.cancel();
    }
}
