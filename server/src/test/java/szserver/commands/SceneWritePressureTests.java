package szserver.commands;
import java.util.*;
import java.util.concurrent.*;
public final class SceneWritePressureTests {
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        var queue=new SceneWriteQueue<Integer>();var gate=new CompletableFuture<Integer>();
        var results=new ArrayList<CompletableFuture<Integer>>();var starts=new ArrayList<List<Integer>>();
        for(int editor=0;editor<8;editor++){
            UUID id=UUID.randomUUID();var order=new ArrayList<Integer>();starts.add(order);
            for(int n=0;n<128;n++){int number=n;results.add(queue.submit(id,33,()->{order.add(number);return gate;}));}
            check(!results.get(results.size()-1).isCompletedExceptionally(),"eight independent full native selections admitted");
            check(queue.submit(id,33,()->gate).isCompletedExceptionally(),"per-editor count remains bounded");
        }
        check(queue.submit(UUID.randomUUID(),33,()->gate).isCompletedExceptionally(),"global count remains bounded");
        check(starts.stream().allMatch(s->s.equals(List.of(0))),"FIFO holds all later requests behind first accepted write");
        gate.complete(7);CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(2,TimeUnit.SECONDS);
        for(var order:starts){check(order.size()==128,"all admitted requests survive burst");for(int n=0;n<128;n++)check(order.get(n)==n,"per-editor ordering preserved");}
        check(queue.drain(100)&&queue.metrics().get("pending").equals(0),"shutdown drains admitted writes");
        var bytes=new SceneWriteQueue<Integer>();var held=new CompletableFuture<Integer>();
        for(int editor=0;editor<8;editor++){
            UUID id=UUID.randomUUID();for(int n=0;n<16;n++)check(!bytes.submit(id,65500,()->held).isCompletedExceptionally(),"bounded large payload admitted");
            check(bytes.submit(id,65500,()->held).isCompletedExceptionally(),"one MiB per editor unchanged");
        }
        check(bytes.submit(UUID.randomUUID(),65500,()->held).isCompletedExceptionally(),"eight MiB total unchanged");
        held.complete(1);check(bytes.drain(100),"byte-limited queue drains");
        var closing=new SceneWriteQueue<Integer>();var finish=new CompletableFuture<Integer>();UUID owner=UUID.randomUUID();
        var admitted=new ArrayList<CompletableFuture<Integer>>();for(int n=0;n<128;n++)admitted.add(closing.submit(owner,33,()->finish));
        var shutdown=CompletableFuture.supplyAsync(()->{try{return closing.drain(2000);}catch(InterruptedException e){throw new CompletionException(e);}});
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(!closing.submit(UUID.randomUUID(),33,()->CompletableFuture.completedFuture(0)).isCompletedExceptionally()){if(System.nanoTime()>until)throw new AssertionError("shutdown did not fence admission");Thread.yield();}
        check(!shutdown.isDone(),"shutdown keeps accepted queue alive");finish.complete(1);
        check(shutdown.get(2,TimeUnit.SECONDS)&&admitted.stream().allMatch(CompletableFuture::isDone),"admitted writes finish after shutdown closes admission");
        var readOrder=new SceneWriteQueue<Integer>();var create=new CompletableFuture<Integer>();UUID creator=UUID.randomUUID();var committed=readOrder.submit(creator,32,()->create);var read=readOrder.barrier(creator);check(!read.isDone(),"Inspector read waits for preceding creation commit");check(readOrder.barrier(UUID.randomUUID()).isDone(),"unrelated editor reads remain independent");create.complete(1);read.get(2,TimeUnit.SECONDS);check(committed.isDone(),"read cannot overtake creation");var failedCreate=new CompletableFuture<Integer>();readOrder.submit(creator,32,()->failedCreate);var afterFailure=readOrder.barrier(creator);failedCreate.completeExceptionally(new IllegalArgumentException("invalid asset"));afterFailure.get(2,TimeUnit.SECONDS);check(readOrder.drain(100),"failed creation does not wedge subsequent reads");
        System.out.println("PASS concurrent native selections, per-editor FIFO, global/per-editor count and unchanged byte bounds");
    }
}
