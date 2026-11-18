package szserver.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Held before opening any store, across recovery and the entire close sequence. */
public final class ServerDataLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private ServerDataLease(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
    public static ServerDataLease acquire(Path data,Path config)throws IOException {
        Files.createDirectories(data);
        FileChannel channel=FileChannel.open(data.resolve("server-owner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {lock=channel.tryLock();}catch(OverlappingFileLockException occupied){lock=null;}
            if(lock==null)throw new IOException("server_data_already_owned: "+data.toAbsolutePath());
            try {
                byte[] bytes=("pid="+ProcessHandle.current().pid()+"\nconfig="+config.toAbsolutePath().normalize()+"\n").getBytes(StandardCharsets.UTF_8);
                channel.truncate(0);channel.position(0);ByteBuffer buffer=ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining())channel.write(buffer);
                channel.force(true);return new ServerDataLease(channel,lock);
            }catch(IOException|RuntimeException error){lock.release();throw error;}
        }catch(IOException|RuntimeException error){channel.close();throw error;}
    }
    @Override public void close()throws IOException {try{lock.release();}finally{channel.close();}}
}
