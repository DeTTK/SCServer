package szserver.world;

import java.io.*;
import java.lang.ref.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** A captured index keeps its process lease alive even after its writer closes. */
final class NativeIndexLeases {
    private static final Cleaner CLEANER=Cleaner.create();
    private static final Map<Path,WeakReference<Context>> contexts=new HashMap<>();
    private record Release(FileChannel channel,FileLock lock) implements Runnable {public void run(){try{lock.release();}catch(IOException ignored){}try{channel.close();}catch(IOException ignored){}}}
    static synchronized Context acquire(Path data)throws IOException{
        contexts.entrySet().removeIf(e->e.getValue().get()==null);var ref=contexts.get(data);Context context=ref==null?null:ref.get();
        if(context==null){context=new Context(data);contexts.put(data,new WeakReference<>(context));}context.writer();return context;
    }
    static final class Context {
        private final List<WeakReference<NativeCommitIndex>> roots=new ArrayList<>();private boolean writer;
        private final Cleaner.Cleanable cleanable;
        Context(Path data)throws IOException{
            Path file=data.resolve("native-map-owner.lock");FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock lock=null;
            try{try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){}if(lock==null)throw new IOException("native_map_owner_busy");cleanable=CLEANER.register(this,new Release(channel,lock));}
            catch(IOException|RuntimeException|Error failed){if(lock!=null)lock.release();channel.close();throw failed;}
        }
        synchronized void writer()throws IOException{if(writer)throw new IOException("native_map_writer_busy");writer=true;}
        synchronized void releaseWriter(){writer=false;}
        synchronized NativeCommitIndex retain(NativeCommitIndex index){index.lease(this);roots.removeIf(r->r.get()==null);roots.add(new WeakReference<>(index));return index;}
        synchronized Set<Path> retained(){Set<Path> result=new HashSet<>();Iterator<WeakReference<NativeCommitIndex>> rows=roots.iterator();while(rows.hasNext()){NativeCommitIndex root=rows.next().get();if(root==null)rows.remove();else result.addAll(root.paths());}return result;}
        synchronized int readers(){roots.removeIf(r->r.get()==null);return roots.size();}
    }
    private NativeIndexLeases(){}
}
