package playerscale;

import java.util.*;

/** Game-thread state. Connection identity prevents old network callbacks entering a new world. */
public final class ScaleReplica {
    private static final int LIMIT=2048;
    private record Entry(long revision,ScaleValue scale,boolean removed){}
    private Object connection;
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();
    private long expiredRevision=-1;
    public void connection(Object current){if(current!=connection){entries.clear();expiredRevision=-1;connection=current;}}
    public boolean receive(Object source,ScaleProtocol.Snapshot packet){
        if(connection==null||source!=connection||packet.revision()<=expiredRevision)return false;
        Entry previous=entries.get(packet.entity());if(previous!=null&&previous.revision()>=packet.revision())return false;
        if(previous==null&&entries.size()>=LIMIT){
            var iterator=entries.entrySet().iterator();boolean freed=false;
            while(iterator.hasNext()){Entry old=iterator.next().getValue();if(old.removed()){expiredRevision=Math.max(expiredRevision,old.revision());iterator.remove();freed=true;break;}}
            if(!freed)return false;
        }
        // Keep removal revisions as tombstones: a delayed SET must not resurrect a removed actor.
        entries.remove(packet.entity());entries.put(packet.entity(),new Entry(packet.revision(),packet.operation()==ScaleProtocol.REMOVE?ScaleValue.NORMAL:packet.scale(),packet.operation()==ScaleProtocol.REMOVE));return true;
    }
    public ScaleValue scale(int entity){Entry entry=entries.get(entity);return entry==null?ScaleValue.NORMAL:entry.scale();}
    public int retained(){return entries.size();}
}
