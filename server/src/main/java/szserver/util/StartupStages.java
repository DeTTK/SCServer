package szserver.util;

import java.util.*;
import java.util.concurrent.Callable;

/** Single startup owner; published timings distinguish recovery from live simulation. */
public final class StartupStages {
    public interface Action {void run() throws Exception;}
    private final long began=System.nanoTime();
    private final Map<String,Double> prepared=new LinkedHashMap<>(),started=new LinkedHashMap<>();
    private volatile Map<String,Object> view=Map.of("phase","preparing");
    public <T> T prepare(String name,Callable<T> factory)throws Exception{
        long at=System.nanoTime();publish("preparing",name);
        System.out.println("Preparing server component: "+name);
        try{T result=factory.call();prepared.put(name,(System.nanoTime()-at)/1e6);publish("prepared",name);return result;}
        catch(Exception|Error error){publish("failed",name);System.err.println("Server preparation failed: "+name);throw error;}
    }
    public void start(String name,Action action)throws Exception{
        long at=System.nanoTime();publish("starting",name);
        try{action.run();started.put(name,(System.nanoTime()-at)/1e6);publish("started",name);}
        catch(Exception|Error error){publish("failed",name);System.err.println("Server start failed: "+name);throw error;}
    }
    public void running(){publish("running","");}
    private void publish(String phase,String component){view=Map.of("phase",phase,"component",component,"elapsedMs",(System.nanoTime()-began)/1e6,"preparationMs",Collections.unmodifiableMap(new LinkedHashMap<>(prepared)),"startMs",Collections.unmodifiableMap(new LinkedHashMap<>(started)));}
    public Map<String,Object> metrics(){return view;}
}
