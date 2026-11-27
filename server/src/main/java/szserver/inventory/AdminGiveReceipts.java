package szserver.inventory;

import com.google.gson.JsonObject;
import szserver.commands.CommandHistory;
import szserver.persistence.Journal;
import java.nio.file.Path;
import java.util.Map;

/** Bounded cold index of durable command identities. Callers are startup/HTTP/command workers. */
public final class AdminGiveReceipts implements AutoCloseable {
    private final Journal journal;private final CommandHistory index;private final SessionReceipts session;
    public AdminGiveReceipts(Path data)throws Exception{this(data,0);}
    public AdminGiveReceipts(Path data,long testDelay)throws Exception{
        session=null;
        offOwner();journal=new Journal(data.resolve("admin-give.sjournal"),testDelay,event->{});CommandHistory opened=null;
        try{opened=new CommandHistory(journal,1024*1024);index=opened;journal.replayLocated((ref,event)->{AdminGiveReceipt.read(event);index.register(ref,event,false);}).get();}
        catch(Exception|Error error){if(opened!=null)opened.close();journal.close();throw error;}
    }
    private AdminGiveReceipts(){journal=null;index=null;session=new SessionReceipts();}
    public static AdminGiveReceipts inMemory(){return new AdminGiveReceipts();}
    private JsonObject findEvent(String id){return session==null?index.raw(id):session.find(id);}
    private void recordEvent(JsonObject event)throws Exception{
        if(session!=null)session.record(event);
        else index.register(journal.appendLocated(event).get(),event,false);
    }
    private Map<String,Object> journalMetrics(){return session==null?journal.metrics():Map.of("storage","memory");}
    private Map<String,Object> indexMetrics(){return session==null?index.metrics():session.metrics();}
    private boolean storageHealthy(){return session!=null||journal.healthy()&&index.healthy();}
    private static void offOwner(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("admin_give_io_on_owner");}
    public AdminGiveReceipt find(String id){offOwner();var e=findEvent(id);return e==null?null:AdminGiveReceipt.read(e);}
    public void record(AdminGiveReceipt r)throws Exception{offOwner();AdminGiveReceipt old=find(r.commandId());if(old!=null){if(!old.equals(r))throw new IllegalArgumentException("give_receipt_conflict");return;}var e=r.event();recordEvent(e);}
    public boolean healthy(){return storageHealthy();}
    public Map<String,Object> metrics(){return Map.of("healthy",healthy(),"index",indexMetrics(),"journal",journalMetrics());}
    @Override public void close()throws Exception{if(session==null)try{journal.close();}finally{index.close();}}
}
