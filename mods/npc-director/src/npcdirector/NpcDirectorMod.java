package npcdirector;
import szmods.api.*;
public final class NpcDirectorMod implements ClientMod {
    public void onLoad(ModContext context)throws Exception{context.log("NPC Director: persistent server programs, native actor steering");NpcBridge.startQa();}
    public void onFrame(ClientFrame frame){NpcBridge.frame(frame.game());}
}
