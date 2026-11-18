package szserver.inventory;

import szserver.commands.CommandHistory;
import szserver.persistence.Journal;
import java.nio.file.Path;
import java.util.Map;

/** Bounded cold index of durable command identities. Callers are startup/HTTP/command workers. */
public final class AdminGiveReceipts implements AutoCloseable {
    private final Journal journal;private final CommandHistory index;
    public AdminGiveReceipts(Path data)throws Exception{this(data,0);}
    public AdminGiveReceipts(Path data,long testDelay)throws Exception{
        offOwner();journal=new Journal(data.resolve("admin-give.sjournal"),testDelay,event->{});CommandHistory opened=null;
        try{opened=new CommandHistory(journal,1024*1024);index=opened;journal.replayLocated((ref,event)->{AdminGiveReceipt.read(event);index.register(ref,event,false);}).get();}
        catch(Exception|Error error){if(opened!=null)opened.close();journal.close();throw error;}
    }
    private static void offOwner(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("admin_give_io_on_owner");}
    public AdminGiveReceipt find(String id){offOwner();var e=index.raw(id);return e==null?null:AdminGiveReceipt.read(e);}
    public void record(AdminGiveReceipt r)throws Exception{offOwner();AdminGiveReceipt old=find(r.commandId());if(old!=null){if(!old.equals(r))throw new IllegalArgumentException("give_receipt_conflict");return;}var e=r.event();journal.appendLocated(e).thenAccept(ref->index.register(ref,e,false)).get();}
    public boolean healthy(){return journal.healthy()&&index.healthy();}
    public Map<String,Object> metrics(){return Map.of("healthy",healthy(),"index",index.metrics(),"journal",journal.metrics());}
    @Override public void close()throws Exception{try{journal.close();}finally{index.close();}}
}
