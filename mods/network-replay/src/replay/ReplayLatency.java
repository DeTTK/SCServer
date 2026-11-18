package replay;

import java.util.*;

/** RTT belongs to the capture clock; echoed wall-clock tokens only identify requests. */
final class ReplayLatency {
    interface Display { int ping()throws Exception; int lag()throws Exception; void set(int ping,int lag)throws Exception; }
    private record Request(int connection,long token){}
    private final Map<Request,Long> pending=new HashMap<>();
    private final Display display;
    private boolean bound;
    private int savedPing,savedLag;
    int pingMillis=-1;
    long samples,unmatched;
    ReplayLatency(Display display){this.display=display;}
    static ReplayLatency nativeDisplay(){return new ReplayLatency(new Display(){
        public int ping()throws Exception{return ((Number)Native.field(Native.type("1337534439"),"if")).intValue();}
        public int lag()throws Exception{return ((Number)Native.field(Native.type("1337534439"),"float")).intValue();}
        public void set(int ping,int lag)throws Exception{Class<?> type=Native.type("1337534439");type.getField("if").setInt(null,ping);type.getField("float").setInt(null,lag);}
    });}
    void bind(DemoFile demo)throws Exception {
        for(String name:List.of("1337534439","974185560","782134028","1482463136"))demo.verifyClass(name);
        if(!bound){savedPing=display.ping();savedLag=display.lag();bound=true;}
        pending.clear();pingMillis=-1;display.set(-1,0);
    }
    void reset(){pending.clear();samples=unmatched=0;pingMillis=-1;}
    void request(int connection,long token,long timeNs){pending.put(new Request(connection,token),timeNs);}
    void response(int connection,long token,long timeNs)throws Exception {
        Long start=pending.remove(new Request(connection,token));
        if(start==null||timeNs<start){unmatched++;return;}
        pingMillis=(int)Math.min(Integer.MAX_VALUE,(timeNs-start)/1_000_000L);
        samples++;display.set(pingMillis,0);
    }
    void close()throws Exception {pending.clear();if(bound){try{display.set(savedPing,savedLag);}finally{bound=false;}}}
}
