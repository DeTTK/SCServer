package szserver.net;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/** Disposable persistent AVL directory. Roots are immutable offsets; only bounded pages are resident. */
final class DiskAccountTree implements AutoCloseable {
    record Node(String key,long body,long left,long right,long count,int height){}
    record Row(String key,byte[] value){}
    private static final int MAGIC=0x535a4154,NODES=2048,BODIES=128;
    private static final long BYTE_LIMIT=32L*1024*1024;
    static final int BULK_MIB_PER_SECOND=16;
    private static final long BULK_BURST=128*1024;
    /** Background copy gets bounded bursts; reset credit after each pause so stalls never accumulate catch-up work. */
    private static final class CopyBudget {
        private long began=System.nanoTime(),bytes;
        void copied(long size)throws IOException{
            bytes=Math.addExact(bytes,size);if(bytes<BULK_BURST)return;
            long expected=bytes*1_000_000_000L/(BULK_MIB_PER_SECOND*1024L*1024L);
            for(long remaining=expected-(System.nanoTime()-began);remaining>0;remaining=expected-(System.nanoTime()-began)){
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("account_index_copy_interrupted");
                java.util.concurrent.locks.LockSupport.parkNanos(remaining);
            }
            began=System.nanoTime();bytes=0;
        }
    }
    private final FileChannel file;private final Path path;
    private final LinkedHashMap<Long,Node> nodes=new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<Long,byte[]> bodies=new LinkedHashMap<>(16,.75f,true);
    private long end=8,bodyBytes,reads,writes;private boolean closed,retired;private int leases;
    private java.util.function.Consumer<Change> listener;private Runnable retirement=()->{};
    record Change(String table,String key,byte[] value){}
    private volatile Map<String,Object> view=Map.of();
    DiskAccountTree(Path path)throws IOException{this(path,0);}
    DiskAccountTree(Path path,long sealedEnd)throws IOException{
        this.path=path.toAbsolutePath();
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)))throw new IOException("account_index_not_regular");
        if(Files.exists(path)&&Files.size(path)>0){try(FileChannel previous=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){ByteBuffer header=ByteBuffer.allocate(8);while(header.hasRemaining()){if(previous.read(header)<0)throw new IOException("account_index_foreign");}header.flip();if(header.getInt()!=MAGIC||header.getInt()!=1)throw new IOException("account_index_foreign");}}
        if(sealedEnd!=0){if(sealedEnd<8||Files.size(path)<sealedEnd)throw new IOException("account_index_sealed_length");file=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);file.truncate(sealedEnd);end=sealedEnd;file.force(true);}
        else{file=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);ByteBuffer head=ByteBuffer.allocate(8).putInt(MAGIC).putInt(1);head.flip();write(head,0);}
        publish();
    }
    private static void guard(){String thread=Thread.currentThread().getName();if(thread.equals("native-game-owner")||thread.equals("world-tick"))throw new IllegalStateException("account_index_owner_io");}
    private void write(ByteBuffer data,long at)throws IOException{while(data.hasRemaining()){int n=file.write(data,at);if(n<=0)throw new EOFException("account_index_write");at+=n;}}
    private void read(ByteBuffer data,long at)throws IOException{while(data.hasRemaining()){int n=file.read(data,at);if(n<=0)throw new EOFException("account_index_read");at+=n;}}
    private long append(int kind,byte[] bytes)throws IOException{
        if(closed)throw new IOException("account_index_closed");int size=Math.addExact(bytes.length,1);CRC32C crc=new CRC32C();crc.update(kind);crc.update(bytes);
        long at=end;ByteBuffer out=ByteBuffer.allocate(Math.addExact(size,8)).putInt(size).putInt((int)crc.getValue()).put((byte)kind).put(bytes);out.flip();write(out,at);end+=size+8L;writes++;return at;
    }
    private byte[] record(long at,int kind)throws IOException{
        if(closed||at<8||at>=end)throw new IOException("account_index_pointer:"+at+":"+end+":"+closed+":"+path.getFileName());ByteBuffer head=ByteBuffer.allocate(8);read(head,at);head.flip();int size=head.getInt(),expected=head.getInt();
        if(size<1||size>Integer.MAX_VALUE-8||at+8L+size>end)throw new IOException("account_index_length");ByteBuffer in=ByteBuffer.allocate(size);read(in,at+8);byte[] data=in.array();CRC32C crc=new CRC32C();crc.update(data);if(data[0]!=(byte)kind||(int)crc.getValue()!=expected)throw new IOException("account_index_crc");reads++;return Arrays.copyOfRange(data,1,size);
    }
    private Node node(long at)throws IOException{
        if(at==0)return null;Node cached=nodes.get(at);if(cached!=null)return cached;ByteBuffer in=ByteBuffer.wrap(record(at,1));
        if(in.remaining()<40)throw new IOException("account_index_node");long body=in.getLong(),left=in.getLong(),right=in.getLong(),count=in.getLong();int height=in.getInt(),size=in.getInt();
        if(size<0||size!=in.remaining()||height<1||height>128||count<1||body<8||body>=at||left<0||left>=at||right<0||right>=at)throw new IOException("account_index_node");byte[] text=new byte[size];in.get(text);Node result=new Node(new String(text,StandardCharsets.UTF_8),body,left,right,count,height);cacheNode(at,result);return result;
    }
    private void cacheNode(long at,Node row){if(retired)return;nodes.put(at,row);while(nodes.size()>NODES)nodes.remove(nodes.keySet().iterator().next());}
    private byte[] body(long at)throws IOException{
        byte[] cached=bodies.get(at);if(cached!=null)return cached;byte[] result=record(at,2);
        if(!retired&&result.length<=BYTE_LIMIT){bodies.put(at,result);bodyBytes+=result.length;while(bodies.size()>BODIES||bodyBytes>BYTE_LIMIT){long oldest=bodies.keySet().iterator().next();bodyBytes-=bodies.remove(oldest).length;}}
        return result;
    }
    private int height(long at)throws IOException{return at==0?0:node(at).height;}
    private long count(long at)throws IOException{return at==0?0:node(at).count;}
    private long branch(String key,long body,long left,long right)throws IOException{
        long count=Math.addExact(1,Math.addExact(count(left),count(right)));int height=1+Math.max(height(left),height(right));byte[] text=key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer data=ByteBuffer.allocate(Math.addExact(40,text.length)).putLong(body).putLong(left).putLong(right).putLong(count).putInt(height).putInt(text.length).put(text);
        long at=append(1,data.array());cacheNode(at,new Node(key,body,left,right,count,height));return at;
    }
    private long balanced(String key,long body,long left,long right)throws IOException{
        int difference=height(left)-height(right);
        if(difference>1){Node l=node(left);if(height(l.left)<height(l.right)){Node m=node(l.right);left=branch(m.key,m.body,branch(l.key,l.body,l.left,m.left),m.right);l=node(left);}return branch(l.key,l.body,l.left,branch(key,body,l.right,right));}
        if(difference< -1){Node r=node(right);if(height(r.right)<height(r.left)){Node m=node(r.left);right=branch(m.key,m.body,m.left,branch(r.key,r.body,m.right,r.right));r=node(right);}return branch(r.key,r.body,branch(key,body,left,r.left),r.right);}
        return branch(key,body,left,right);
    }
    private long change(long root,String key,long body)throws IOException{
        if(root==0)return body==0?0:branch(key,body,0,0);Node n=node(root);int order=key.compareTo(n.key);
        if(order<0){long next=change(n.left,key,body);return next==n.left?root:balanced(n.key,n.body,next,n.right);}
        if(order>0){long next=change(n.right,key,body);return next==n.right?root:balanced(n.key,n.body,n.left,next);}
        if(body!=0)return branch(key,body,n.left,n.right);
        if(n.left==0)return n.right;if(n.right==0)return n.left;Node next=node(n.right);while(next.left!=0)next=node(next.left);return balanced(next.key,next.body,n.left,change(n.right,next.key,0));
    }
    long put(long root,String key,byte[] value){guard();synchronized(this){try{long next=change(root,key,value==null?0:append(2,value));publish();return next;}catch(IOException error){throw new UncheckedIOException(error);}}}
    byte[] get(long root,String key){guard();synchronized(this){try{while(root!=0){Node n=node(root);int order=key.compareTo(n.key);if(order==0){byte[] value=body(n.body);publish();return value;}root=order<0?n.left:n.right;}publish();return null;}catch(IOException error){throw new UncheckedIOException(error);}}}
    long size(long root){guard();synchronized(this){try{return count(root);}catch(IOException error){throw new UncheckedIOException(error);}}}
    Iterator<Row> iterator(long root){guard();return new Iterator<>(){
        final ArrayDeque<Long> stack=new ArrayDeque<>();long cursor=root;
        public boolean hasNext(){guard();synchronized(DiskAccountTree.this){try{while(cursor!=0){stack.push(cursor);cursor=node(cursor).left;}return !stack.isEmpty();}catch(IOException error){throw new UncheckedIOException(error);}}}
        public Row next(){if(!hasNext())throw new NoSuchElementException();synchronized(DiskAccountTree.this){try{Node n=node(stack.pop());cursor=n.right;Row result=new Row(n.key,body(n.body));publish();return result;}catch(IOException error){throw new UncheckedIOException(error);}}}
    };}
    private void publish(){view=Map.of("diskBytes",end,"cachedNodes",nodes.size(),"nodeLimit",NODES,"cachedBodies",bodies.size(),"bodyLimit",BODIES,"cachedBodyBytes",bodyBytes,"bodyByteLimit",BYTE_LIMIT,"reads",reads,"writes",writes);}
    Map<String,Object> metrics(){return view;}
    Path path(){return path;}
    synchronized long end(){return end;}
    synchronized AutoCloseable pin(){if(closed||retired)throw new IllegalStateException("account_index_retired");leases++;java.util.concurrent.atomic.AtomicBoolean released=new java.util.concurrent.atomic.AtomicBoolean();return ()->{if(released.compareAndSet(false,true))release();};}
    private synchronized void release()throws IOException{if(--leases<0)throw new IllegalStateException("account_index_lease_underflow");if(retired&&leases==0)finishRetirement();}
    synchronized void retire(Runnable cleanup)throws IOException{if(retired)return;retired=true;listener=null;retirement=cleanup;nodes.clear();bodies.clear();bodyBytes=0;publish();if(leases==0)finishRetirement();}
    private void finishRetirement()throws IOException{close();retirement.run();}
    void observe(java.util.function.Consumer<Change> listener){this.listener=listener;}
    void changed(String table,String key,byte[] value){var target=listener;if(table!=null&&target!=null)target.accept(new Change(table,key,value));}
    long bulk(long count,Iterator<Row> rows){guard();synchronized(this){try{String[] previous={null};long root=bulk(count,rows,previous,new CopyBudget());if(rows.hasNext())throw new IOException("account_index_bulk_count");publish();return root;}catch(IOException error){throw new UncheckedIOException(error);}}}
    private long bulk(long count,Iterator<Row> rows,String[] previous,CopyBudget budget)throws IOException{
        if(count<0)throw new IOException("account_index_bulk_count");if(count==0)return 0;long left=bulk(count/2,rows,previous,budget);if(!rows.hasNext())throw new IOException("account_index_bulk_count");Row row=rows.next();if(previous[0]!=null&&previous[0].compareTo(row.key)>=0)throw new IOException("account_index_bulk_order");previous[0]=row.key;long body=append(2,row.value);budget.copied(row.value.length+49L+row.key.length()*3L);long right=bulk(count-count/2-1,rows,previous,budget);return branch(row.key,body,left,right);
    }
    long forcePrefix()throws IOException{long length; synchronized(this){if(closed)throw new IOException("account_index_closed");length=end;}file.force(true);return length;}
    @Override public synchronized void close()throws IOException{if(closed)return;closed=true;nodes.clear();bodies.clear();bodyBytes=0;file.close();publish();}
}
