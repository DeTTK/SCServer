package szserver.net;

import szserver.inventory.*;import java.nio.file.*;import java.util.*;
public final class InventoryTransferQueueTests{
    static int checks;static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
    static int metric(InventoryTransferQueue<?,?> q,String k){return ((Number)q.metrics().get(k)).intValue();}
    public static void main(String[] args)throws Exception{
        var q=new InventoryTransferQueue<String,Integer>(3,12,4,16);
        check(q.offer("a",10,1,4,0)==InventoryTransferQueue.Admission.ACCEPTED,"admit first");check(q.offer("a",99,1,4,0)==InventoryTransferQueue.Admission.DUPLICATE,"suppress in-flight duplicate");q.offer("a",20,2,4,1);q.offer("a",30,3,4,2);check(q.offer("a",40,4,1,3)==InventoryTransferQueue.Admission.FULL,"owner count cap");
        check(q.pending("a",1)&&!q.pending("b",1),"only originating session has pending sequence");
        var first=q.start("a",2);check(q.pending("a",1),"active sequence remains pending until durable completion");check(first.stream().map(InventoryTransferQueue.Entry::value).toList().equals(List.of(10,20)),"FIFO batch order");check(q.start("a",2).isEmpty(),"only one active durable batch");check(metric(q,"retained")==3&&metric(q,"retainedBytes")==12,"active work stays budgeted");
        check(q.offer("b",1,1,4,0)==InventoryTransferQueue.Admission.ACCEPTED,"same sequence belongs to another owner");check(q.offer("c",2,2,1,0)==InventoryTransferQueue.Admission.FULL,"global admission bound");var other=q.start("b",2);check(other.size()==1,"other owner independent while first persists");q.complete("b",other);q.complete("a",first);check(!q.pending("a",1),"durable completion releases the acknowledged sequence");
        check(q.start("a",2).get(0).value()==30,"tail starts after durable completion");q.discard("a");check(metric(q,"retained")==0&&metric(q,"retainedBytes")==0,"disconnect releases every reservation");
        q.offer("a",40,1,4,4);var next=q.start("a",2);check(!q.complete("a",first)&&metric(q,"retained")==1,"late callback cannot complete a new lane");q.complete("a",next);q.offer("a",1,1,9,1);check(q.offer("a",2,2,4,2)==InventoryTransferQueue.Admission.FULL,"owner byte cap");q.offer("a",3,null,1,3);var active=q.start("a",1);check(q.cancelWaiting("a").size()==1&&metric(q,"retained")==1,"dependent tail cancellation retains active transaction");q.complete("a",active);check(metric(q,"owners")==0,"empty owner does not leak");
        // Fifty native bulk requests share one validated delta and one durable revision.
        Path root=Path.of(args[0]);Files.createDirectories(root);Path defs=root.resolve("items.json");Files.writeString(defs,"{\"1\":{\"type\":\"medicine\",\"stack_size\":64}}");ItemCatalog catalog=new ItemCatalog(defs);Map<Inventory.Address,ItemStack> rows=new LinkedHashMap<>();for(int i=0;i<50;i++)rows.put(new Inventory.Address(1,0,i),new ItemStack(1,64,0,new byte[0]));Inventory before=new Inventory(catalog).replace(rows,0).after();
        var bulk=new InventoryTransferQueue<String,Integer>(64,4096,128,8192);for(int i=0;i<50;i++)bulk.offer("actor",i,i,32,i);var batch=bulk.start("actor",64);check(batch.size()==50,"one native bulk action fits the batch budget");
        Inventory working=before;Map<Inventory.Address,ItemStack> changes=new LinkedHashMap<>();for(var e:batch){var step=working.quick(new Inventory.Address(1,0,e.value()),List.of(new Inventory.Address(2,0,0)),0);working=step.after();changes.putAll(step.changes());}var plan=before.replace(changes,3200);
        check(plan.after().revision()==before.revision()+1&&plan.after().items().size()==1&&plan.after().at(new Inventory.Address(2,0,0)).count()==3200,"complete bulk plan conserves all items in one revision");
        try(var journal=new InventoryJournal(root.resolve("characters.sjournal"),catalog)){UUID actor=UUID.randomUUID();journal.commit(actor,new Inventory(catalog).replace(rows,0)).get();journal.commit(actor,plan).get();check(journal.load(actor).at(new Inventory.Address(2,0,0)).count()==3200,"bulk persists before acknowledgement");bulk.complete("actor",batch);check(metric(bulk,"retained")==0,"durable completion releases entire bulk budget");}
        var wire=new java.io.ByteArrayOutputStream();try(var out=new java.io.DataOutputStream(wire)){out.writeShort(-1);out.writeShort(42);out.writeByte(0);out.writeByte(0);out.writeShort(9);out.writeByte(1);out.writeShort(0);out.writeInt(0);}
        var noRoom=InventoryPackets.read(2009,wire.toByteArray(),3);check(noRoom.destinations().isEmpty()&&noRoom.source().equals(new Inventory.Address(2,3,9))&&noRoom.sequence()==42,"native no-room quick request still decodes for acknowledgement");
        System.out.println("PASS "+checks+" inventory transfer FIFO, duplicate, budget, disconnect and durable 50-request bulk checks");
    }
}
