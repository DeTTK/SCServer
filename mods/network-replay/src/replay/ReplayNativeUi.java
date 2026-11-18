package replay;

/** The indexed world has zero simulation ticks. Native screens still need their
 * ordinary GUI update at real 20 Hz, including during pause and reverse. */
final class ReplayNativeUi implements ReplayRender.UiTicks {
    final java.util.function.LongSupplier nanos;
    Object timer;int tickBase;long clockBegan;double timeBase;
    ReplayNativeUi(){this(System::nanoTime);}
    ReplayNativeUi(java.util.function.LongSupplier nanos){this.nanos=nanos;}
    void bind(Object timer)throws Exception {
        this.timer=timer;tickBase=((Number)Native.field(timer,"else")).intValue();timeBase=((Number)Native.field(timer,"char")).doubleValue();clockBegan=nanos.getAsLong();
        ReplayRender.uiTicks=this;
    }
    public int sample(Object value,int original){
        if(value!=timer||timer==null)return original;
        return (int)Math.min(Integer.MAX_VALUE,(long)tickBase+Math.max(0,nanos.getAsLong()-clockBegan)/50_000_000L);
    }
    public double time(Object value,double original){return value==timer&&timer!=null?timeBase+Math.max(0,nanos.getAsLong()-clockBegan)/1e9:original;}
    void close()throws Exception {
        if(timer!=null)Native.reflectedField(timer,"else").setInt(timer,sample(timer,tickBase));
        timer=null;screen=null;lastNs=0;if(ReplayRender.uiTicks==this)ReplayRender.uiTicks=null;
    }
    Object screen;long lastNs;
    java.util.Map<String,Object> diagnostic()throws Exception {
        Object tools=Native.field(Native.type("925863226"),"void");
        return java.util.Map.of("hud_hidden",(boolean)Native.call(Native.field(Native.type("1546247182"),"k"),"transient","boolean"),
                "tools_menu",(boolean)Native.field(tools,"interface"),"tools_capture",(boolean)Native.call(tools,"for","boolean"),
                "photo_mode",(boolean)Native.call(Native.type("753624521"),"catch","boolean"),"tick",sample(timer,tickBase));
    }
    void frame(Object game,long now)throws Exception {
        Object current=Native.call(game,"float","622415611");
        if(current==screen&&lastNs!=0&&now-lastNs<50_000_000L)return;
        screen=current;lastNs=now;
        // This client updates the Alt tools manager from the simulation tick.
        // Its own GUI update must continue when indexed playback freezes that
        // tick; calling the whole native tick would also advance the world.
        Native.call(Native.field(Native.type("925863226"),"void"),"while","void");
        // The canonical method updates only the current screen hierarchy.
        // It does not tick world entities, the connection, physics or weather.
        if(current!=null)Native.call(game,"instanceof","void");
    }
}
