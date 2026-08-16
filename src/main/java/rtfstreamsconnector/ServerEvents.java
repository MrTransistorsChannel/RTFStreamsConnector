package rtfstreamsconnector;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import rtfstreamsconnector.commands.DumpRiverNetworkCommand;
import rtfstreamsconnector.commands.DumpRiversFileCommand;
import rtfstreamsconnector.commands.ProbeCommand;
import rtfstreamsconnector.rivers.ChunkRiverCache;
import rtfstreamsconnector.rivers.RiverDistanceCache;
import rtfstreamsconnector.util.RTFHelpers;

@EventBusSubscriber(modid = RTFStreamsConnectorMod.MOD_ID)
public final class ServerEvents {
    private ServerEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent e) {
        DumpRiverNetworkCommand.register(e.getDispatcher());
        DumpRiversFileCommand.register(e.getDispatcher());
        ProbeCommand.register(e.getDispatcher());
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level) {
            RTFHelpers.dropContinent(level);
            RiverDistanceCache.untrackLevel(level);
            ChunkRiverCache.untrackLevel(level);
        }
    }
}
