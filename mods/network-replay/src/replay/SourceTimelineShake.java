package replay;

import com.google.gson.JsonObject;

/** Source 560967359 uses the existing native noise/envelope implementation. */
final class SourceTimelineShake implements SourceTimelineRunner.Handler {
    interface Access {
        boolean inside()throws Exception;
        Object start(JsonObject definition)throws Exception;
        void stop(Object effect)throws Exception;
    }
    final Access access;final boolean continuous;final JsonObject definition;Object effect;
    SourceTimelineShake(SourceWeatherProgram.Element event,Access access){this.access=access;continuous=event.continuous();definition=event.action().getAsJsonObject("shake");}
    void start()throws Exception {effect=access.start(definition.deepCopy());}
    void stop()throws Exception {try{if(effect!=null)access.stop(effect);}finally{effect=null;}}
    public void enter(long elapsed,float progress)throws Exception {if(access.inside())start();}
    public void update(long elapsed,float progress)throws Exception {if(!continuous)return;boolean inside=access.inside();if(inside&&effect==null)start();else if(!inside&&effect!=null)stop();}
    public void exit(long elapsed,float progress)throws Exception {try{if(continuous)stop();}finally{effect=null;}}
}
