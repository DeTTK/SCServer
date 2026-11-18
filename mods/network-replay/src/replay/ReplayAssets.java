package replay;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Warm the native graph pool before starting the user's playback clock. */
final class ReplayAssets {
    record Key(Object manager,Object definition){}
    static final class Spec {
        Object manager,resource;Map<?,?> overrides;boolean mode;int peak;
    }
    final Map<Key,Spec> graphs=new LinkedHashMap<>();
    CompletableFuture<Void> ready;
    void observe(Collection<?> controllers)throws Exception {
        Map<Key,Integer> counts=new HashMap<>();
        for(Object controller:controllers){
            Object request=Native.field(controller,"default"),manager=Native.field(request,"extends"),definition=Native.field(request,"default"),strategy=Native.field(request,"continue");
            String type=strategy.getClass().getName();Map<?,?> overrides;
            boolean mode=false;
            if(type.equals("1386819415")){overrides=(Map<?,?>)Native.field(strategy,"native");mode=(boolean)Native.field(strategy,"do");}
            else if(type.equals("1319951970"))overrides=(Map<?,?>)Native.field(strategy,"public");
            else throw new IllegalStateException("Unknown native graph pool strategy: "+type);
            Key key=new Key(manager,definition);Spec spec=graphs.get(key);
            if(spec==null){spec=new Spec();spec.manager=manager;spec.resource=Native.field(controller,"void");spec.overrides=overrides;spec.mode=mode;graphs.put(key,spec);}
            counts.merge(key,1,Integer::sum);
        }
        for(var entry:counts.entrySet())graphs.get(entry.getKey()).peak=Math.max(graphs.get(entry.getKey()).peak,entry.getValue());
    }
    static void verify(DemoFile demo)throws Exception {
        demo.verifyDependency("1782100073","0a5070177838868c4d0742836a727730e001289aaaf0573e01eab411fdd62ad1");
        for(String type:List.of("1386819415","1319951970","1382586603","947953390"))demo.verifyClass(type);
    }
    void warm(DemoFile demo)throws Exception {
        verify(demo);
        List<CompletableFuture<?>> pending=new ArrayList<>();
        for(Spec spec:graphs.values())pending.add((CompletableFuture<?>)Native.call(spec.manager,"this","java.util.concurrent.CompletableFuture",spec.resource,spec.peak,spec.overrides,spec.mode));
        ready=CompletableFuture.allOf(pending.toArray(CompletableFuture<?>[]::new));
    }
    boolean finished()throws Exception {
        if(ready==null||!ready.isDone())return false;
        try{ready.join();}catch(java.util.concurrent.CompletionException failure){throw new java.io.IOException("Native replay resource preparation failed",failure.getCause());}
        return true;
    }
}
