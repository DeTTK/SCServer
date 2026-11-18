package replay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Time index plus independently checked value pages. No simulation snapshots. */
final class ScenePagedArchive {
    static final int MAGIC=0x53535047,VERSION=1,PAGE_TARGET=64*1024,MAX_PAGE=33*1024*1024;
    static final int MAX_KEYS=16_000_000,MAX_PAGES=1_000_000;
    static final long MAX_INDEX=512L*1024*1024,MAX_PAYLOAD=256L*1024*1024*1024;
    record Page(int size,byte[] hash){}
    record Reference(int page,int offset,int length){}
    static String pageName(int id){return String.format(Locale.ROOT,"p/%08x",id);}
    static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static int count(int value,int limit)throws IOException {if(value<0||value>limit)throw new IOException("Indexed scene allocation limit");return value;}
    static void write(Path path,SceneTracks scene)throws IOException {
        Path absolute=path.toAbsolutePath();Files.createDirectories(absolute.getParent());
        Path archive=Files.createTempFile(absolute.getParent(),"scene-pages-",".tmp"),indexFile=Files.createTempFile(absolute.getParent(),"scene-index-",".tmp");
        try{
            MessageDigest indexHash=sha();
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(archive)))){
                Writer pages=new Writer(zip);
                // Keep values needed by the same displayed instant on adjacent
                // pages. Track-major storage makes one frame decompress hundreds
                // of distant pages, and evicts them before the next frame.
                record Cursor(SceneTracks.Track track,int key,int order){}
                Map<SceneTracks.Track,Reference[]> references=new IdentityHashMap<>();
                PriorityQueue<Cursor> pending=new PriorityQueue<>(Comparator.<Cursor>comparingLong(c->c.track().timeAt(c.key())).thenComparingInt(Cursor::order));
                long allocatedKeys=0;int order=0;
                for(var object:scene.objects)for(var track:object.channels().values())if(!references.containsKey(track)){
                    if((allocatedKeys+=track.keys.size())>MAX_KEYS)throw new IOException("Indexed scene key limit");
                    references.put(track,new Reference[track.keys.size()]);pending.add(new Cursor(track,0,order++));
                }
                while(!pending.isEmpty()){
                    Cursor cursor=pending.remove();SceneTracks.Track track=cursor.track();int key=cursor.key();
                    references.get(track)[key]=pages.add(track.keys.get(key).value());
                    if(key+1<track.keys.size())pending.add(new Cursor(track,key+1,cursor.order()));
                }
                pages.flush();
                try(DataOutputStream index=new DataOutputStream(new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(indexFile)),indexHash))){
                    index.writeInt(MAGIC);index.writeInt(VERSION);index.writeUTF(scene.eventHash);index.writeUTF(scene.compatibilityFingerprint);index.writeLong(scene.durationNs);
                    int mask=0;for(var domain:scene.coverage)mask|=1<<domain.ordinal();index.writeInt(mask);
                    count(scene.objects.size(),SceneTrackArchive.MAX_OBJECTS);index.writeInt(scene.objects.size());long totalKeys=0;
                    for(var object:scene.objects){
                        index.writeInt(object.id().session());index.writeInt(object.id().networkId());index.writeInt(object.id().generation());index.writeUTF(object.type());index.writeLong(object.bornNs());index.writeLong(object.deadNs());
                        count(object.channels().size(),4096);index.writeInt(object.channels().size());
                        for(var channel:new TreeMap<>(object.channels()).entrySet()){
                            var track=channel.getValue();if((totalKeys+=track.keys.size())>MAX_KEYS)throw new IOException("Indexed scene key limit");
                            index.writeUTF(channel.getKey());index.writeByte(track.domain.ordinal());index.writeByte(track.blend.ordinal());index.writeInt(track.keys.size());
                            for(int k=0;k<track.keys.size();k++){
                                var key=track.keys.get(k);Reference ref=references.get(track)[k];index.writeLong(key.timeNs());index.writeBoolean(key.cut());index.writeInt(ref.page());index.writeInt(ref.offset());index.writeInt(ref.length());
                                int type=key.value() instanceof SceneTracks.Numbers?0:key.value() instanceof SceneTracks.Bytes?1:2;index.writeByte(type);
                                if(type==1){var bytes=(SceneTracks.Bytes)key.value();index.writeInt(bytes.size());index.writeInt(bytes.hashCode());}
                            }
                        }
                    }
                    pages.flush();index.writeInt(pages.table.size());
                    for(Page page:pages.table){index.writeInt(page.size());index.write(page.hash());}
                }
                if(Files.size(indexFile)>MAX_INDEX)throw new IOException("Indexed scene metadata limit");
                zip.putNextEntry(new ZipEntry("index.bin"));Files.copy(indexFile,zip);zip.closeEntry();
                zip.putNextEntry(new ZipEntry("index.sha256"));zip.write(HexFormat.of().formatHex(indexHash.digest()).getBytes(StandardCharsets.US_ASCII));zip.closeEntry();
            }
            Files.move(archive,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(archive);Files.deleteIfExists(indexFile);}
    }
    static final class Writer {
        final ZipOutputStream zip;final List<Page> table=new ArrayList<>();ByteArrayOutputStream bytes=new ByteArrayOutputStream(PAGE_TARGET);long total;
        Writer(ZipOutputStream zip){this.zip=zip;}
        Reference add(SceneTracks.Value value)throws IOException {
            var encoded=new ByteArrayOutputStream();try(var out=new DataOutputStream(encoded)){SceneTrackArchive.writeValue(out,value);}
            byte[] payload=encoded.toByteArray();if(payload.length>MAX_PAGE)throw new IOException("Indexed scene value limit");
            if(bytes.size()>0&&(long)bytes.size()+payload.length>PAGE_TARGET)flush();
            Reference ref=new Reference(table.size(),bytes.size(),payload.length);bytes.write(payload);return ref;
        }
        void flush()throws IOException {
            if(bytes.size()==0)return;if(table.size()>=MAX_PAGES||(total+=bytes.size())>MAX_PAYLOAD)throw new IOException("Indexed scene page limit");
            byte[] data=bytes.toByteArray();zip.putNextEntry(new ZipEntry(pageName(table.size())));zip.write(data);zip.closeEntry();table.add(new Page(data.length,sha().digest(data)));bytes=new ByteArrayOutputStream(PAGE_TARGET);
        }
    }
    static final class Store implements AutoCloseable {
        final ZipFile zip;List<Page> table=List.of();
        final LinkedHashMap<Integer,byte[]> pages=new LinkedHashMap<>(16,.75f,true);
        final LinkedHashMap<Reference,SceneTracks.Value> decoded=new LinkedHashMap<>(16,.75f,true);
        volatile Map<Reference,SceneTracks.Value> resident=Map.of();
        long pageBytes,decodedBytes,pageReads,peakPageBytes,peakDecodedBytes,decodedHits,decodedMisses,decodedEvictions;boolean closed;
        static final long PAGE_BUDGET=64L*1024*1024,VALUE_BUDGET=64L*1024*1024;
        Store(ZipFile zip){this.zip=zip;}
        byte[] page(int id)throws IOException {
            synchronized(this){if(closed)throw new IOException("Closed indexed scene archive");byte[] cached=pages.get(id);if(cached!=null)return cached;}
            if(id<0||id>=table.size())throw new IOException("Invalid scene page reference");Page info=table.get(id);ZipEntry entry=zip.getEntry(pageName(id));
            if(entry==null||entry.getSize()!=info.size())throw new IOException("Scene page size changed");byte[] value;
            try(InputStream input=zip.getInputStream(entry)){value=input.readNBytes(info.size()+1);}
            if(value.length!=info.size()||!MessageDigest.isEqual(sha().digest(value),info.hash()))throw new IOException("Scene page integrity failure");
            synchronized(this){if(closed)throw new IOException("Closed indexed scene archive");byte[] cached=pages.get(id);if(cached!=null)return cached;
                pageReads++;while(!pages.isEmpty()&&pageBytes+value.length>PAGE_BUDGET){var oldest=pages.entrySet().iterator();var item=oldest.next();pageBytes-=item.getValue().length;oldest.remove();}
                pages.put(id,value);pageBytes+=value.length;peakPageBytes=Math.max(peakPageBytes,pageBytes);return value;}
        }
        DataInputStream slice(Reference ref)throws IOException {
            byte[] data=page(ref.page());if(ref.offset()<0||ref.length()<1||(long)ref.offset()+ref.length()>data.length)throw new IOException("Invalid scene value reference");
            return new DataInputStream(new ByteArrayInputStream(data,ref.offset(),ref.length()));
        }
        byte[] binary(Reference ref,int length)throws IOException {
            SceneTracks.Value retained=resident.get(ref);if(retained instanceof SceneTracks.Bytes bytes){if(bytes.size()!=length)throw new IOException("Resident binary shape changed");return bytes.data();}
            try(var input=slice(ref)){if(input.readUnsignedByte()!=1||input.readInt()!=length)throw new IOException("Indexed binary shape changed");byte[] data=input.readNBytes(length);if(data.length!=length||input.read()!=-1)throw new IOException("Indexed binary boundary mismatch");return data;}
        }
        SceneTracks.Value value(Reference ref,int type,SceneTracks.Blend blend)throws IOException {
            SceneTracks.Value retained=resident.get(ref);if(retained!=null){validate(retained,type,blend);return retained;}
            synchronized(this){if(closed)throw new IOException("Closed indexed scene archive");SceneTracks.Value cached=decoded.get(ref);if(cached!=null){decodedHits++;validate(cached,type,blend);return cached;}decodedMisses++;}
            SceneTracks.Value value;try(var input=slice(ref)){
                input.mark(1);if(input.readUnsignedByte()!=type)throw new IOException("Indexed scene value type changed");input.reset();value=SceneTrackArchive.readValue(input);if(input.read()!=-1)throw new IOException("Indexed value trailing data");
            }
            validate(value,type,blend);
            long size=SceneTrackArchive.valueBytes(value)+64;
            synchronized(this){if(closed)throw new IOException("Closed indexed scene archive");SceneTracks.Value cached=decoded.get(ref);if(cached!=null){validate(cached,type,blend);return cached;}
                while(!decoded.isEmpty()&&(decodedBytes+size>VALUE_BUDGET||decoded.size()>=100000)){var oldest=decoded.entrySet().iterator();var item=oldest.next();decodedBytes-=SceneTrackArchive.valueBytes(item.getValue())+64;oldest.remove();decodedEvictions++;}
                if(size<=VALUE_BUDGET){decoded.put(ref,value);decodedBytes+=size;peakDecodedBytes=Math.max(peakDecodedBytes,decodedBytes);}return value;}
        }
        static void validate(SceneTracks.Value value,int type,SceneTracks.Blend blend)throws IOException {
            int actual=value instanceof SceneTracks.Numbers?0:value instanceof SceneTracks.Bytes?1:2;
            if(actual!=type||blend==SceneTracks.Blend.BONES&&actual!=2||blend!=SceneTracks.Blend.HOLD&&blend!=SceneTracks.Blend.BONES&&actual!=0)throw new IOException("Indexed channel/value codec mismatch");
        }
        record Warm(Reference ref,int type,SceneTracks.Blend blend){}
        final LinkedHashSet<Warm> warmingUrgent=new LinkedHashSet<>(),warmingAhead=new LinkedHashSet<>();final Set<Warm> queued=new HashSet<>();
        static Warm take(LinkedHashSet<Warm> values){var iterator=values.iterator();Warm task=iterator.next();iterator.remove();return task;}
        Thread warmWorker;boolean warmClosed;IOException warmFailure;long warmLoads;
        synchronized boolean prepare(Reference ref,int type,SceneTracks.Blend blend,boolean urgent)throws IOException {
            if(closed||warmClosed)throw new IOException("Closed indexed scene archive");if(warmFailure!=null)throw warmFailure;
            var retained=resident.get(ref);if(retained!=null){validate(retained,type,blend);return true;}
            if(type==1){if(pages.get(ref.page())!=null)return true;}
            else {var cached=decoded.get(ref);if(cached!=null){validate(cached,type,blend);return true;}}
            Warm task=new Warm(ref,type,blend);if(queued.add(task)){(urgent?warmingUrgent:warmingAhead).add(task);}else if(urgent&&warmingAhead.remove(task))warmingUrgent.add(task);
            // Selected dependencies take priority without a linear scan or LIFO starvation.
            while(warmingUrgent.size()+warmingAhead.size()>8192){Warm old=take(warmingAhead.isEmpty()?warmingUrgent:warmingAhead);queued.remove(old);}
            if(warmWorker==null){warmWorker=new Thread(this::warm,"Replay scene page preparation");warmWorker.setDaemon(true);warmWorker.start();}notifyAll();return false;
        }
        private void warm(){
            while(true){Warm task;synchronized(this){while(warmingUrgent.isEmpty()&&warmingAhead.isEmpty()&&!warmClosed)try{wait();}catch(InterruptedException ignored){}if(warmClosed)return;task=take(warmingUrgent.isEmpty()?warmingAhead:warmingUrgent);}
                try{if(task.type()==1)page(task.ref().page());else value(task.ref(),task.type(),task.blend());synchronized(this){warmLoads++;}}catch(IOException failure){synchronized(this){warmFailure=failure;}}
                finally{synchronized(this){queued.remove(task);}}
            }
        }
        public void close()throws IOException {
            Thread worker;synchronized(this){if(closed)return;warmClosed=true;warmingUrgent.clear();warmingAhead.clear();queued.clear();notifyAll();worker=warmWorker;}
            if(worker!=null)try{worker.join();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException("Interrupted scene preparation cleanup",failure);}
            synchronized(this){closed=true;resident=Map.of();pages.clear();decoded.clear();pageBytes=decodedBytes=0;}zip.close();
        }
    }
    static final class Keys extends AbstractList<SceneTracks.Key> {
        final Store store;final SceneTracks.Blend blend;final long[] times;final int[] page,offset,length,binaryLength,binaryHash;final byte[] type,cut;
        final Map<Integer,SceneTracks.Bytes> binaries=new HashMap<>();
        final Reference[] references;
        boolean allBinary;
        Keys(Store store,SceneTracks.Blend blend,int size){this.store=store;this.blend=blend;times=new long[size];page=new int[size];offset=new int[size];length=new int[size];type=new byte[size];cut=new byte[size];binaryLength=new int[size];binaryHash=new int[size];references=new Reference[size];}
        public int size(){return times.length;}
        Reference reference(int index){Reference cached=references[index];if(cached==null||cached.page()!=page[index]||cached.offset()!=offset[index]||cached.length()!=length[index])references[index]=cached=new Reference(page[index],offset[index],length[index]);return cached;}
        boolean prepare(int index,boolean urgent)throws IOException {return store.prepare(reference(index),type[index],blend,urgent);}
        public SceneTracks.Key get(int index){
            Objects.checkIndex(index,size());Reference ref=reference(index);
            try{
                SceneTracks.Value value;
                if(type[index]==1){
                    synchronized(binaries){value=binaries.computeIfAbsent(index,k->new SceneTracks.Bytes(binaryLength[k],binaryHash[k],()->{try{return store.binary(ref,binaryLength[k]);}catch(IOException failure){throw new UncheckedIOException(failure);}}));}
                }else value=store.value(ref,type[index],blend);
                return new SceneTracks.Key(times[index],value,cut[index]!=0);
            }catch(IOException failure){throw new UncheckedIOException(failure);}
        }
        void validate()throws IOException {
            allBinary=true;
            for(int k=0;k<size();k++){
                if(type[k]!=1)allBinary=false;
                if(page[k]<0||page[k]>=store.table.size()||offset[k]<0||length[k]<1||(long)offset[k]+length[k]>store.table.get(page[k]).size())throw new IOException("Indexed scene reference outside page");
                if(type[k]==1&&(blend!=SceneTracks.Blend.HOLD||binaryLength[k]<0||binaryLength[k]>32*1024*1024||length[k]!=5L+binaryLength[k]))throw new IOException("Invalid indexed binary descriptor");
                if(type[k]>2||type[k]<0||blend==SceneTracks.Blend.BONES&&type[k]!=2||blend!=SceneTracks.Blend.HOLD&&blend!=SceneTracks.Blend.BONES&&type[k]!=0)throw new IOException("Invalid indexed value type");
            }
        }
    }
    static SceneTracks read(Path path)throws IOException {
        ZipFile zip=new ZipFile(path.toFile());Store store=new Store(zip);boolean success=false;
        try{
            Set<String> names=new HashSet<>();for(var entries=zip.entries();entries.hasMoreElements();)if(!names.add(entries.nextElement().getName()))throw new IOException("Duplicate indexed scene entry");
            ZipEntry indexEntry=zip.getEntry("index.bin"),hashEntry=zip.getEntry("index.sha256");
            if(indexEntry==null||indexEntry.getSize()<0||indexEntry.getSize()>MAX_INDEX||hashEntry==null||hashEntry.getSize()!=64)throw new IOException("Indexed scene metadata size limit");
            byte[] expected;try(var input=zip.getInputStream(hashEntry)){expected=input.readNBytes(65);}MessageDigest digest=sha();List<Keys> lists=new ArrayList<>();SceneTracks scene;
            try(var input=new DataInputStream(new DigestInputStream(new BufferedInputStream(zip.getInputStream(indexEntry)),digest))){
                if(input.readInt()!=MAGIC||input.readInt()!=VERSION)throw new IOException("Unsupported indexed scene archive");
                String eventHash=input.readUTF(),fingerprint=input.readUTF();long duration=input.readLong();int mask=input.readInt();if((mask>>>SceneTracks.Domain.values().length)!=0)throw new IOException("Unknown indexed scene domain");
                Set<SceneTracks.Domain> coverage=EnumSet.noneOf(SceneTracks.Domain.class);for(var domain:SceneTracks.Domain.values())if((mask&(1<<domain.ordinal()))!=0)coverage.add(domain);
                int count=count(input.readInt(),SceneTrackArchive.MAX_OBJECTS);List<SceneTracks.ObjectTrack> objects=new ArrayList<>(count);long totalKeys=0;
                for(int n=0;n<count;n++){
                    var id=new SceneTracks.ObjectId(input.readInt(),input.readInt(),input.readInt());String type=input.readUTF();long born=input.readLong(),dead=input.readLong();int channels=count(input.readInt(),4096);Map<String,SceneTracks.Track> tracks=new TreeMap<>();
                    for(int c=0;c<channels;c++){
                        String name=input.readUTF();int domain=input.readUnsignedByte(),blend=input.readUnsignedByte();if(domain>=SceneTracks.Domain.values().length||blend>=SceneTracks.Blend.values().length)throw new IOException("Invalid indexed channel codec");
                        int keys=count(input.readInt(),MAX_KEYS);if((totalKeys+=keys)>MAX_KEYS||totalKeys>indexEntry.getSize()/22)throw new IOException("Indexed scene key budget exceeded");
                        Keys list=new Keys(store,SceneTracks.Blend.values()[blend],keys);
                        for(int k=0;k<keys;k++){list.times[k]=input.readLong();list.cut[k]=(byte)(input.readBoolean()?1:0);list.page[k]=input.readInt();list.offset[k]=input.readInt();list.length[k]=input.readInt();list.type[k]=input.readByte();if(list.type[k]==1){list.binaryLength[k]=input.readInt();list.binaryHash[k]=input.readInt();}}
                        if(tracks.put(name,new SceneTracks.Track(SceneTracks.Domain.values()[domain],list.blend,list,list.times))!=null)throw new IOException("Duplicate indexed channel");lists.add(list);
                    }
                    objects.add(new SceneTracks.ObjectTrack(id,type,born,dead,tracks));
                }
                int pages=count(input.readInt(),MAX_PAGES);List<Page> table=new ArrayList<>(pages);long total=0;
                for(int n=0;n<pages;n++){int size=count(input.readInt(),MAX_PAGE);if(size==0||(total+=size)>MAX_PAYLOAD)throw new IOException("Indexed scene page size limit");byte[] hash=input.readNBytes(32);if(hash.length!=32)throw new EOFException("Truncated scene page hash");table.add(new Page(size,hash));}
                if(input.read()!=-1)throw new IOException("Indexed scene trailing metadata");store.table=List.copyOf(table);scene=new SceneTracks(duration,eventHash,fingerprint,coverage,objects);
            }
            if(!HexFormat.of().formatHex(digest.digest()).equals(new String(expected,StandardCharsets.US_ASCII)))throw new IOException("Indexed scene metadata integrity failure");
            if(names.size()!=store.table.size()+2)throw new IOException("Unexpected indexed scene entries");
            for(int n=0;n<store.table.size();n++){var page=zip.getEntry(pageName(n));if(page==null||page.getSize()!=store.table.get(n).size())throw new IOException("Missing indexed scene page");}
            for(Keys list:lists)list.validate();scene.storage=store;success=true;return scene;
        }catch(IllegalArgumentException failure){throw new IOException("Invalid indexed scene metadata",failure);}
        finally{if(!success)store.close();}
    }
}
