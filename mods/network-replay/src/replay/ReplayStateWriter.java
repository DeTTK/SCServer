package replay;

import java.nio.file.*;
import java.io.IOException;
import java.util.function.Supplier;

/** Coalesced diagnostic snapshots. Disk latency never stalls the rendering thread. */
final class ReplayStateWriter implements AutoCloseable {
    final Path path;final Thread worker;Supplier<String> pending;boolean closed;volatile IOException failure;
    ReplayStateWriter(Path path){this.path=path;worker=new Thread(this::run,"Replay diagnostic writer");worker.setDaemon(true);worker.start();}
    void submit(String value){submit(()->value);}
    /** Transfers a private snapshot; its serializer must not read live game state. */
    synchronized void submit(Supplier<String> value){if(closed)return;pending=value;notifyAll();}
    private void run(){
        while(true){Supplier<String> next;synchronized(this){while(pending==null&&!closed)try{wait();}catch(InterruptedException ignored){}if(pending==null&&closed)return;next=pending;pending=null;}
            Path temporary=path.resolveSibling(path.getFileName()+".pending");
            try{Files.writeString(temporary,next.get());try{Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}failure=null;}catch(Exception e){failure=e instanceof IOException io?io:new IOException("Replay diagnostic serialization failed",e);}
        }
    }
    public void close()throws InterruptedException {synchronized(this){closed=true;notifyAll();}worker.join();}
}
