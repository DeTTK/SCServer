package replay;

import java.time.*;
import java.util.*;

/** Client event lifecycle from source 870803843; frontend actions stay in recorded packets. */
final class SourceTimelineRunner {
    interface Handler {
        void enter(long elapsed,float progress)throws Exception;
        void update(long elapsed,float progress)throws Exception;
        void exit(long elapsed,float progress)throws Exception;
    }
    interface Access {
        boolean admit(SourceWeatherProgram.Element event)throws Exception;
        Handler create(SourceWeatherProgram.Element event,UUID instance)throws Exception;
    }
    record Active(SourceWeatherProgram.Element event,Handler handler) {}
    final Access access;
    final LinkedHashMap<String,Active> active=new LinkedHashMap<>();
    Duration previous;
    UUID instance;
    int revision;
    long enters,updates,exits;
    SourceTimelineRunner(Access access){this.access=access;}
    static boolean clientAction(SourceWeatherProgram.Element event){return Set.of("perlin_shake","play_sound","set_weather_phenomenon_weight").contains(event.type);}
    static boolean eligible(SourceWeatherProgram.Element event,Duration current,Duration previous){
        return event.continuous()?event.contains(current):event.at.compareTo(previous)>0&&event.at.compareTo(current)<=0;
    }
    void dispatch(Active value,Duration position,int phase)throws Exception {
        if(value.handler()==null)return;
        long elapsed=Math.max(0,position.toMillis()-value.event().at.toMillis());float progress=value.event().progress(position);
        switch(phase){case 0->{value.handler().enter(elapsed,progress);enters++;}case 1->{value.handler().update(elapsed,progress);updates++;}case 2->{value.handler().exit(elapsed,progress);exits++;}default->throw new AssertionError();}
    }
    void reset()throws Exception {
        Duration position=previous==null?Duration.ZERO:previous;
        try{for(Active value:new ArrayList<>(active.values()))dispatch(value,position,2);}
        finally{active.clear();previous=null;revision=0;}
    }
    void clear()throws Exception {try{reset();}finally{instance=null;}}
    void step(SourceWeather.WorldTimeline state,Instant now,SourceWeatherProgram.Program program,boolean worldReady)throws Exception {
        if(!worldReady)return;
        if(!Objects.equals(instance,state.instance())){reset();instance=state.instance();}
        if(!state.active()){reset();return;}
        if(program==null)return;
        UUID id=Objects.requireNonNull(state.instance(),"Active source timeline instance");
        Duration current=Duration.ofMillis(state.positionMillis(now));
        if(revision!=state.revision()){revision=state.revision();previous=current;}
        Duration before=previous==null?current.minusSeconds(2):previous;previous=current;
        List<SourceWeatherProgram.Element> incoming=new ArrayList<>();
        for(SourceWeatherProgram.Element event:program.elements())if(!active.containsKey(event.id)&&eligible(event,current,before)&&clientAction(event)&&access.admit(event))incoming.add(event);
        for(var iterator=active.values().iterator();iterator.hasNext();){Active value=iterator.next();if(!eligible(value.event(),current,before)){iterator.remove();dispatch(value,current,2);}}
        for(SourceWeatherProgram.Element event:incoming){Active value=new Active(event,access.create(event,id));active.put(event.id,value);dispatch(value,current,0);}
        for(Active value:new ArrayList<>(active.values()))dispatch(value,current,1);
        for(var iterator=active.values().iterator();iterator.hasNext();){Active value=iterator.next();if(!value.event().continuous()){iterator.remove();dispatch(value,current,2);}}
    }
}
