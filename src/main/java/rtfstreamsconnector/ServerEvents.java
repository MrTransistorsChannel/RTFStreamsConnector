package rtfstreamsconnector;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import rtfstreamsconnector.commands.DumpRiverNetworkCommand;

@EventBusSubscriber(modid = RTFStreamsConnectorMod.MOD_ID)
public final class ServerEvents {
    private ServerEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent e) {
        DumpRiverNetworkCommand.register(e.getDispatcher());
    }
}
