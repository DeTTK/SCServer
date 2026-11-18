package szserver.world;

import szserver.util.Json;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.stream.Stream;

/** Disposable, explicitly owned unacknowledged delta output. The native writer lease serializes use. */
final class NativeCandidateFiles implements AutoCloseable {
    static final String OWNER=".candidate-owner.bin";
    private static final int MAGIC=0x534e434f,VERSION=1,MARKER_BYTES=104,SCAN_ENTRIES=4096,DELETE_FILES=512,SCAN_DIRECTORIES=128;
    private static final long BUDGET=250_000_000L;
    private final Path root;private final UUID identity;private final byte[] worldHash,bindingHash;
    private DirectoryStream<Path> listing;private Iterator<Path> cursor;private Candidate current;
    private long claimed,recovered,bytes,files,unowned,protectedCount,invalid,errors,rounds;
    private volatile Map<String,Object> view=Map.of("status","idle");
    private static final class Candidate {final String id;Path path;Stream<Path> scan;Iterator<Path> iterator;boolean validated;Candidate(String id,Path path){this.id=id;this.path=path;}void close(){if(scan!=null)scan.close();scan=null;iterator=null;}}
    NativeCandidateFiles(Path data,String world,String binding)throws IOException{
        NativeTileStates.worker();root=data.resolve("native-map-commits");worldHash=digest(world);bindingHash=digest(binding);
        Path guard=data.resolve("native-map-candidate.guard");UUID value;
        if(!Files.exists(guard,LinkOption.NOFOLLOW_LINKS)){
            value=UUID.randomUUID();Path pending=data.resolve(".native-map-candidate-guard-"+UUID.randomUUID());write(pending,ByteBuffer.allocate(24).putInt(MAGIC).putInt(VERSION).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array());
            Files.move(pending,guard,StandardCopyOption.ATOMIC_MOVE);
        }
        byte[] raw=read(guard,24);ByteBuffer in=ByteBuffer.wrap(raw);if(in.getInt()!=MAGIC||in.getInt()!=VERSION)throw new IOException("native_candidate_guard_version");identity=new UUID(in.getLong(),in.getLong());publish();
    }
    private static byte[] digest(String text){try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static void real(Path p)throws IOException{if(Files.isSymbolicLink(p)||!p.toRealPath().equals(p.toAbsolutePath().normalize()))throw new IOException("native_candidate_alias");}
    private static byte[] read(Path p,int size)throws IOException{real(p);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)!=size)throw new IOException("native_candidate_marker_size");return Files.readAllBytes(p);}
    private static void write(Path p,byte[] raw)throws IOException{try(FileChannel out=FileChannel.open(p,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){ByteBuffer bytes=ByteBuffer.wrap(raw);while(bytes.hasRemaining())out.write(bytes);out.force(true);}}
    private static String id(String text)throws IOException{try{if(!UUID.fromString(text).toString().equals(text))throw new IllegalArgumentException();return text;}catch(RuntimeException bad){throw new IOException("native_candidate_id",bad);}}
    private void sameGuard()throws IOException{ByteBuffer in=ByteBuffer.wrap(read(root.getParent().resolve("native-map-candidate.guard"),24));if(in.getInt()!=MAGIC||in.getInt()!=VERSION||!new UUID(in.getLong(),in.getLong()).equals(identity))throw new IOException("native_candidate_guard_changed");}
    void claim(Path pending,String generation)throws IOException{
        NativeTileStates.worker();sameGuard();id(generation);if(!pending.equals(root.resolve(".pending-"+generation)))throw new IOException("native_candidate_namespace");real(pending);
        try(var entries=Files.newDirectoryStream(pending)){if(entries.iterator().hasNext())throw new IOException("native_candidate_not_new");}
        UUID key=UUID.fromString(generation);ByteBuffer marker=ByteBuffer.allocate(MARKER_BYTES).putInt(MAGIC).putInt(VERSION).putLong(identity.getMostSignificantBits()).putLong(identity.getLeastSignificantBits()).putLong(key.getMostSignificantBits()).putLong(key.getLeastSignificantBits()).put(worldHash).put(bindingHash);
        write(pending.resolve(OWNER),marker.array());claimed++;publish();
    }
    private void owned(Path path,String generation)throws IOException{
        sameGuard();real(path);if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!path.getParent().equals(root))throw new IOException("native_candidate_namespace");ByteBuffer in=ByteBuffer.wrap(read(path.resolve(OWNER),MARKER_BYTES));
        if(in.getInt()!=MAGIC||in.getInt()!=VERSION||!new UUID(in.getLong(),in.getLong()).equals(identity)||!new UUID(in.getLong(),in.getLong()).toString().equals(generation))throw new IOException("native_candidate_owner_changed");byte[] wh=new byte[32],bh=new byte[32];in.get(wh).get(bh);if(!Arrays.equals(wh,worldHash)||!Arrays.equals(bh,bindingHash))throw new IOException("native_candidate_source_changed");
    }
    /** A durable ACK protects the generation even if removal of its disposable marker fails. */
    void acknowledged(String generation){
        Path path=root.resolve(generation);if(!Files.exists(path.resolve(OWNER),LinkOption.NOFOLLOW_LINKS))return;
        try{owned(path,generation);Files.delete(path.resolve(OWNER));}catch(IOException|RuntimeException bad){errors++;}publish();
    }
    private static int coordinate(String value)throws IOException{try{int n=Integer.parseInt(value);if(!Integer.toString(n).equals(value))throw new NumberFormatException();return n;}catch(NumberFormatException bad){throw new IOException("native_candidate_coordinate",bad);}}
    private static void regionName(String text,boolean file)throws IOException{String[] p=text.split("\\.");if(p.length!=(file?4:3)||!p[0].equals("r")||(file&&!p[3].equals("szr")))throw new IOException("native_candidate_region_name");coordinate(p[1]);coordinate(p[2]);}
    /** Every leaf and directory is checked before the first deletion; no arbitrary recursion. */
    private static void layout(Path base,Path path)throws IOException{
        real(path);Path rel=base.relativize(path);int count=rel.getNameCount();String text=rel.toString().replace('\\','/');boolean directory=Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS);
        if(path.equals(base))return;
        if(directory){if(text.equals("region")||text.equals("region/tile-states"))return;if(count==3&&rel.getName(0).toString().equals("region")&&rel.getName(1).toString().equals("tile-states")){regionName(rel.getName(2).toString(),false);return;}throw new IOException("native_candidate_foreign_directory");}
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("native_candidate_foreign_leaf");
        if(count==1&&Set.of(OWNER,"snapshot.json","files.jsonl","chunk-revisions.jsonl").contains(text))return;
        if(count==2&&rel.getName(0).toString().equals("region")){if(rel.getName(1).toString().equals("registry.json"))return;regionName(rel.getName(1).toString(),true);return;}
        if(count==4&&rel.getName(0).toString().equals("region")&&rel.getName(1).toString().equals("tile-states")){
            String[] p=rel.getName(3).toString().split("\\.");if(p.length!=4||!p[0].equals("c")||!p[3].equals("bin"))throw new IOException("native_candidate_tile_name");int x=coordinate(p[1]),z=coordinate(p[2]);if(!rel.getName(2).toString().equals("r."+Math.floorDiv(x,32)+"."+Math.floorDiv(z,32)))throw new IOException("native_candidate_tile_home");return;
        }throw new IOException("native_candidate_foreign_file");
    }
    Map<String,Object> sweep(Set<String> confirmed,Set<Path> retained,NativeCommitIndex head){
        NativeTileStates.worker();rounds++;long end=System.nanoTime()+BUDGET;int examined=0;int[] scanRemaining={SCAN_ENTRIES},deleteRemaining={DELETE_FILES};
        try{
            if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)){publish();return view;}real(root);
            if(current!=null&&(confirmed.contains(current.id)||retained.contains(root.resolve(current.id)))){protectedCount++;current.close();current=null;}
            if(current!=null)advance(current,end,head,scanRemaining,deleteRemaining);
            if(current==null){
                if(listing==null){listing=Files.newDirectoryStream(root);cursor=listing.iterator();}
                while(cursor.hasNext()&&examined++<SCAN_DIRECTORIES&&scanRemaining[0]>0&&deleteRemaining[0]>0&&System.nanoTime()<end){
                    Path path=cursor.next();String name=path.getFileName().toString(),generation;
                    try{generation=id(name.startsWith(".pending-")?name.substring(9):name.startsWith(".abandoned-")?name.substring(11):name);}catch(IOException unrelated){continue;}
                    if(confirmed.contains(generation)||retained.contains(root.resolve(generation))){protectedCount++;continue;}
                    if(!Files.exists(path.resolve(OWNER),LinkOption.NOFOLLOW_LINKS)){unowned++;continue;}
                    try{owned(path,generation);current=new Candidate(generation,path);advance(current,end,head,scanRemaining,deleteRemaining);if(current!=null)break;}catch(IOException|RuntimeException unsafe){invalid++;if(current!=null)current.close();current=null;}
                }
                if(!cursor.hasNext()){listing.close();listing=null;cursor=null;}
            }
        }catch(IOException|UncheckedIOException|DirectoryIteratorException failure){errors++;if(current!=null)current.close();current=null;closeListing();}
        publish();return view;
    }
    private void advance(Candidate candidate,long end,NativeCommitIndex head,int[] scanRemaining,int[] deleteRemaining)throws IOException{
        owned(candidate.path,candidate.id);
        if(!candidate.validated){
            if(candidate.scan==null){candidate.scan=Files.walk(candidate.path);candidate.iterator=candidate.scan.iterator();}
            while(candidate.iterator.hasNext()&&scanRemaining[0]>0&&System.nanoTime()<end){layout(candidate.path,candidate.iterator.next());scanRemaining[0]--;}
            if(candidate.iterator.hasNext())return;candidate.close();owned(candidate.path,candidate.id);
            if(candidate.path.getFileName().toString().equals(candidate.id)){
                NativeMapArchive.verify(candidate.path);var manifest=Json.parse(Files.readAllBytes(candidate.path.resolve("snapshot.json")));if(!manifest.get("generation").getAsString().equals(candidate.id)||!Arrays.equals(digest(manifest.get("sourceWorld").getAsString()),worldHash)||!Arrays.equals(digest(manifest.get("sourceBinding").getAsString()),bindingHash))throw new IOException("native_candidate_manifest_source");
                repairLatest(candidate,head);
            }
            Path abandoned=root.resolve(".abandoned-"+candidate.id);if(!candidate.path.equals(abandoned)){Files.move(candidate.path,abandoned,StandardCopyOption.ATOMIC_MOVE);candidate.path=abandoned;}candidate.validated=true;
        }
        if(deleteRemaining[0]==0||System.nanoTime()>=end)return;
        Files.walkFileTree(candidate.path,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException{layout(candidate.path,dir);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{
                layout(candidate.path,file);if(file.getParent().equals(candidate.path)&&file.getFileName().toString().equals(OWNER))return FileVisitResult.CONTINUE;
                long size=Files.size(file);Files.delete(file);bytes+=size;files++;deleteRemaining[0]--;return deleteRemaining[0]==0||System.nanoTime()>=end?FileVisitResult.TERMINATE:FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException failure)throws IOException{if(failure!=null)throw failure;if(!dir.equals(candidate.path)){if(deleteRemaining[0]==0||System.nanoTime()>=end)return FileVisitResult.TERMINATE;Files.delete(dir);deleteRemaining[0]--;}return deleteRemaining[0]==0||System.nanoTime()>=end?FileVisitResult.TERMINATE:FileVisitResult.CONTINUE;}
        });
        try(var entries=Files.newDirectoryStream(candidate.path)){for(Path file:entries)if(!file.getFileName().toString().equals(OWNER))return;}
        if(deleteRemaining[0]<2||System.nanoTime()>=end)return;owned(candidate.path,candidate.id);Files.delete(candidate.path.resolve(OWNER));Files.delete(candidate.path);deleteRemaining[0]-=2;files++;bytes+=MARKER_BYTES;recovered++;current=null;
    }
    /** latest.json is advisory; repair only a pointer to the validated owned orphan. */
    private void repairLatest(Candidate candidate,NativeCommitIndex head)throws IOException{
        Path latest=root.resolve("latest.json");if(!Files.exists(latest,LinkOption.NOFOLLOW_LINKS))return;real(latest);if(Files.size(latest)>4096)throw new IOException("native_candidate_latest_size");var value=Json.parse(Files.readAllBytes(latest));if(!value.get("generation").getAsString().equals(candidate.id))return;if(!value.get("manifestSha256").getAsString().equals(NativeMapArchive.sha(candidate.path.resolve("snapshot.json"))))throw new IOException("native_candidate_latest_changed");
        if(head.generation().isEmpty())Files.delete(latest);else{Path manifest=root.resolve(id(head.generation())).resolve("snapshot.json");real(manifest);Path next=root.resolve(".repair-latest-"+UUID.randomUUID());write(next,Json.bytes(Map.of("generation",head.generation(),"manifestSha256",NativeMapArchive.sha(manifest))));Files.move(next,latest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    }
    private void publish(){view=Map.ofEntries(Map.entry("status",current==null?"idle":current.validated?"deleting":"validating"),Map.entry("claimed",claimed),Map.entry("recovered",recovered),Map.entry("reclaimedBytes",bytes),Map.entry("deletedFiles",files),Map.entry("skippedUnowned",unowned),Map.entry("skippedProtected",protectedCount),Map.entry("skippedInvalid",invalid),Map.entry("errors",errors),Map.entry("rounds",rounds),Map.entry("scanOpen",listing!=null),Map.entry("validationOpen",current!=null&&current.scan!=null),Map.entry("scanEntryLimit",SCAN_ENTRIES),Map.entry("deleteFileLimit",DELETE_FILES),Map.entry("rootEntryLimit",SCAN_DIRECTORIES),Map.entry("timeBudgetMs",250));}
    Map<String,Object> metrics(){return view;}
    private void closeListing(){if(listing!=null)try{listing.close();}catch(IOException ignored){}listing=null;cursor=null;}
    public void close(){if(current!=null)current.close();current=null;closeListing();}
}
