package replay;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import org.apache.commons.lang3.time.DurationFormatUtils;

/** Source HUD keeps elapsed seconds on phase changes, independent of world clock rate. */
final class SourceExtractorHud {
    static final class Timing {
        SourceExtractorState.Phase phase;
        double start,end;
        void update(boolean selected,SourceExtractorState.Phase next,double changed,Duration duration){
            if(!selected){phase=null;start=end=0;return;}
            if(next!=phase){
                phase=next;
                if(next==SourceExtractorState.Phase.LOADING||next==SourceExtractorState.Phase.EXTRACTING){start=changed;end=start+duration.getSeconds();}
            }
        }
        boolean timed(){return (phase==SourceExtractorState.Phase.LOADING||phase==SourceExtractorState.Phase.EXTRACTING)&&start>0&&end>0;}
        float remainingFraction(double now){return Math.min((float)(end-now)/(float)(end-start),1f);}
        long remainingMillis(double now){return (long)Math.max(0,(end-now)*1000);}
    }
    final SourceExtractorController owner;
    final Map<Object,Timing> timings=new IdentityHashMap<>();
    long updates,draws,timedDraws;
    SourceExtractorHud(SourceExtractorController owner){this.owner=owner;}
    void updated(Object hud)throws Exception {
        Timing timing=timings.computeIfAbsent(hud,ignored->new Timing());Object selected=Native.field(hud,"long");
        if(selected==null){timing.update(false,null,0,Duration.ZERO);}
        else{SourceExtractorController.Entry entry=owner.entries.get(selected);if(entry==null){timing.update(false,null,0,Duration.ZERO);}else{entry.sync();timing.update(true,entry.state.phase(),entry.state.changedSeconds(),entry.duration);SourceAdapters.put(hud,"protected",Native.field(selected,"super"));}}
        updates++;
    }
    void clearSelection()throws Exception {
        Object hud=Native.field(Native.type("1651476251"),"if");Native.call(hud,"enum","void");
        for(String field:List.of("long","protected","abstract","strictfp","this","else"))SourceAdapters.put(hud,field,null);
        SourceAdapters.put(hud,"static",false);timings.clear();
    }
    interface Renderer {
        float fade()throws Exception;
        float top(float partial)throws Exception;
        int backgroundWidth()throws Exception;
        int backgroundHeight()throws Exception;
        int counterWidth()throws Exception;
        int counterHeight()throws Exception;
        String title()throws Exception;
        void color(float fade)throws Exception;
        void background(float center,float top)throws Exception;
        void rectangle(double x,double y,double width,double height,int color)throws Exception;
        void counter(int x,int y,int width,int height)throws Exception;
        void timer(String text,double x,double y,int color)throws Exception;
        void label(String text,double x,double y,int color,boolean center)throws Exception;
        void restore()throws Exception;
        void reserve(int height)throws Exception;
    }
    static boolean render(Timing timing,Renderer painter,int width,float partial,double now)throws Exception {
        float fade=painter.fade();painter.color(fade);
        int alpha=(int)(fade*252f+3f)<<24;float center=(float)width/2f,top=painter.top(partial)-8f;
        float height=painter.backgroundHeight();painter.background(center,top);
        boolean timed=timing.timed();
        if(timed){
            float fraction=timing.remainingFraction(now),barWidth=painter.backgroundWidth(),barY=top+(float)painter.backgroundHeight()-5f;
            painter.rectangle(center-barWidth/2f,barY,5d,1d,0xFFFFFF|alpha);
            painter.rectangle(center-barWidth/2f+6f,barY,barWidth-12f,1d,0x565656|alpha);
            painter.rectangle(center-barWidth/2f+6f,barY,(barWidth-12f)*fraction,1d,0xFFFFFF|alpha);
            painter.rectangle(center+barWidth/2f-5f,barY,5d,1d,0xFFFFFF|alpha);
            int counterWidth=painter.counterWidth(),counterHeight=painter.counterHeight();float counterY=barY+10f+(float)(counterHeight/2);
            painter.counter((int)center-counterWidth/2,(int)counterY-counterHeight/2,counterWidth,counterHeight);
            painter.timer(DurationFormatUtils.formatDuration(timing.remainingMillis(now),"mm:ss"),(int)center+1,(int)counterY+2,0xEEEEEE|alpha);
            height+=(float)(10+painter.counterHeight());
        }
        String title=painter.title();if(!title.isEmpty())painter.label(title,(int)center,(int)top+1,0xEEEEEE|alpha,true);
        painter.restore();painter.reserve((int)height);return timed;
    }
    void draw(Object hud,int width,float partial)throws Exception {
        if(Native.field(hud,"long")==null)return;
        Timing timing=timings.computeIfAbsent(hud,ignored->new Timing());
        if(render(timing,new NativeRenderer(hud),width,partial,ReplayClock.seconds))timedDraws++;draws++;
    }
    static final class NativeRenderer implements Renderer {
        final Object hud,painter,background,counter,bounds,counterBounds,placement;
        NativeRenderer(Object hud)throws Exception {
            this.hud=hud;Class<?> type=Native.type("1651476251");painter=Native.field(type,"goto");background=Native.field(type,"enum");counter=Native.field(type,"break");
            bounds=Native.call(background,"strictfp","61438557");counterBounds=Native.field(counter,"void");placement=Native.field(Native.type("1625524896"),"protected");
        }
        public float fade()throws Exception{return ((Number)Native.call(hud,"int","float")).floatValue();}
        public float top(float partial)throws Exception{return ((Number)Native.call(placement,"throws","float","extractor_hud",partial)).floatValue();}
        public int backgroundWidth()throws Exception{return ((Number)Native.field(bounds,"try")).intValue();}
        public int backgroundHeight()throws Exception{return ((Number)Native.field(bounds,"enum")).intValue();}
        public int counterWidth()throws Exception{return ((Number)Native.field(counterBounds,"try")).intValue();}
        public int counterHeight()throws Exception{return ((Number)Native.field(counterBounds,"enum")).intValue();}
        public String title()throws Exception{return (String)Native.call(hud,"package","java.lang.String");}
        public void color(float fade)throws Exception{Native.call(Native.type("1973624787"),"enum","void",1f,1f,1f,fade);}
        public void background(float center,float top)throws Exception{Native.call(painter,"throw","void",center,top,background);}
        public void rectangle(double x,double y,double width,double height,int color)throws Exception{Native.call(painter,"extends","void",x,y,width,height,color);}
        public void counter(int x,int y,int width,int height)throws Exception{Native.call(painter,"strictfp","void",x,y,width,height,counter);}
        Object font(String name)throws Exception{return Native.call(Native.call(painter,"implements","351318308",Native.field(Native.type("747722393"),name)),"native","186969209");}
        public void timer(String text,double x,double y,int color)throws Exception{Native.call(font("a"),"switch","double",text,x,y,color);}
        public void label(String text,double x,double y,int color,boolean center)throws Exception{Native.call(font("instanceof"),"while","double",text,x,y,color,center);}
        public void restore()throws Exception{Native.call(Native.type("1973624787"),"interface","void",-1);}
        public void reserve(int height)throws Exception{Native.call(placement,"class","void","extractor_hud",height);}
    }
}
