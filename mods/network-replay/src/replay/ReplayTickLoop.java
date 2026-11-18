package replay;

import java.util.function.LongSupplier;

/** Resume recorded dispatch between completed native ticks, with bounded frame work. */
final class ReplayTickLoop {
    interface Importer {void run()throws Exception;}
    final Timeline timeline;
    final Timeline.Sink sink;
    final Importer importer;
    final LongSupplier nanos;
    final int maxImports,eventBudget;
    final long frameBudgetNs;
    boolean resuming,started;
    long began,callbacks,imports,noProgress;
    int frameImports,frameEvents,maximumFrameImports;
    int frameImportLimit;
    ReplayTickLoop(Timeline timeline,Timeline.Sink sink,Importer importer){this(timeline,sink,importer,System::nanoTime,32,2048,12_000_000L);}
    ReplayTickLoop(Timeline timeline,Timeline.Sink sink,Importer importer,LongSupplier nanos,int maxImports,int eventBudget,long frameBudgetNs){
        if(maxImports<1||eventBudget<1||frameBudgetNs<1)throw new IllegalArgumentException("Invalid replay frame work budget");
        this.timeline=timeline;this.sink=sink;this.importer=importer;this.nanos=nanos;this.maxImports=maxImports;this.eventBudget=eventBudget;this.frameBudgetNs=frameBudgetNs;
    }
    void beginFrame(){beginFrame(maxImports);}
    void beginFrame(int limit){if(limit<1)throw new IllegalArgumentException();started=true;began=nanos.getAsLong();frameImports=frameEvents=0;frameImportLimit=Math.min(limit,maxImports);}
    boolean completed()throws Exception {
        if(resuming)return false;
        callbacks++;
        if(!timeline.playing&&!timeline.seeking)return false;
        if(!started){began=nanos.getAsLong();started=true;}
        long allowed=timeline.seeking?frameBudgetNs:Math.min(frameBudgetNs,6_000_000L);
        if(frameImports>=(frameImportLimit==0?maxImports:frameImportLimit)||frameEvents>=eventBudget||nanos.getAsLong()-began>=allowed)return false;
        long before=timeline.position;int cursor=timeline.cursor;
        resuming=true;
        try{
            long remaining=allowed-(nanos.getAsLong()-began);if(remaining<=0)return false;
            frameEvents+=timeline.advance(0,Math.min(256,eventBudget-frameEvents),sink,nanos,remaining);
            if(timeline.position==before&&timeline.cursor==cursor){noProgress++;return false;}
            // Native import resets its per-import phase/count and refreshes the
            // real-time baseline before the recorded-clock agent override.
            importer.run();imports++;frameImports++;maximumFrameImports=Math.max(maximumFrameImports,frameImports);
            return true;
        }finally{resuming=false;}
    }
    java.util.Map<String,Object> snapshot(){return java.util.Map.of("completed_tick_callbacks",callbacks,"additional_native_imports",imports,"readiness_no_progress",noProgress,"maximum_imports_per_frame",maximumFrameImports,"frame_budget_ns",frameBudgetNs,"max_imports",maxImports,"event_budget",eventBudget);}
}
