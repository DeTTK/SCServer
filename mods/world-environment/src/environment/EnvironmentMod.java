package environment;
import szmods.api.*;

public final class EnvironmentMod implements ClientMod {
    private boolean qa;
    public void onLoad(ModContext context){EnvironmentBridge.context=context;context.log("Native server environment and weather editor integration loaded");qa=Boolean.getBoolean("sz.client.isolatedTest")&&Integer.getInteger("sz.environment.qa.port",0)>0;try{if(qa)EnvironmentQa.start();}catch(Exception error){throw new IllegalStateException(error);}}
    public void onFrame(ClientFrame frame){EnvironmentBridge.frame(frame.game());if(qa)EnvironmentQa.frame();}
}
