package szserver.world;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Streaming source identity. Bootstrap/checkpoint worker only;region bytes,SQL and provenance. */
final class CheckpointSources {
    private CheckpointSources(){}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    static String textDigest(String text){return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));}
    private static void text(MessageDigest out,String value){byte[] raw=value.getBytes(StandardCharsets.UTF_8);out.update(ByteBuffer.allocate(4).putInt(raw.length).array());out.update(raw);}
    private static void file(MessageDigest out,Path root,Path path,boolean contents)throws IOException{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("checkpoint_source_not_regular");
        BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);MessageDigest bytes=digest();long count=0;
        if(contents){try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("checkpoint_source_interrupted");bytes.update(buffer,0,n);count=Math.addExact(count,n);}}}
        else{count=before.size();text(bytes,before.lastModifiedTime().toString());text(bytes,before.creationTime().toString());text(bytes,Objects.toString(before.fileKey(),""));}
        BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(count!=before.size()||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))throw new IOException("checkpoint_source_changed_during_read");
        text(out,root.relativize(path).toString().replace('\\','/'));out.update(ByteBuffer.allocate(8).putLong(count).array());out.update(bytes.digest());
    }
    private static void tree(MessageDigest out,Path root,Path directory,int depth,long[] count,boolean contents)throws IOException{
        if(depth>8||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("checkpoint_source_directory");
        List<Path> children=new ArrayList<>();try(var stream=Files.newDirectoryStream(directory)){for(Path path:stream){if(children.size()>=65536)throw new IOException("checkpoint_source_directory_limit");children.add(path);}}
        children.sort(Comparator.comparing(p->p.getFileName().toString()));
        for(Path child:children){count[0]=Math.addExact(count[0],1);if(Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS))tree(out,root,child,depth+1,count,contents);else file(out,root,child,contents);}
    }
    private static void map(MessageDigest out,Path root,String registry,boolean contents)throws IOException{
        text(out,root.toString());text(out,registry);List<Path> regions=new ArrayList<>();
        try(var stream=Files.newDirectoryStream(root,"r.*.*.szr")){for(Path path:stream){if(regions.size()>=65536)throw new IOException("checkpoint_source_region_limit");regions.add(path);}}
        regions.sort(Comparator.comparing(p->p.getFileName().toString()));out.update(ByteBuffer.allocate(4).putInt(regions.size()).array());for(Path path:regions)file(out,root,path,contents);
        for(String name:List.of("registry.json","scene-sql-catalog.jsonl")){Path path=name.equals("registry.json")?SceneRegistry.sourceRegistry(root):root.resolve(name);text(out,name);out.update((byte)(Files.exists(path,LinkOption.NOFOLLOW_LINKS)?1:0));if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))file(out,root,path,contents);}
        Path provenance=root.resolve("object-registry-provenance");text(out,"provenance");out.update((byte)(Files.exists(provenance,LinkOption.NOFOLLOW_LINKS)?1:0));if(Files.exists(provenance,LinkOption.NOFOLLOW_LINKS))tree(out,root,provenance,0,new long[1],contents);
        Path completeTiles=root.resolve("tile-states");if(Files.exists(completeTiles,LinkOption.NOFOLLOW_LINKS)){text(out,"native-complete-tiles-v1");tree(out,root,completeTiles,0,new long[1],contents);}
    }
    static String regions(Path root,SceneRegistry registry,Path templates,SceneRegistry templateRegistry,Integer fallback)throws IOException{
        return regions(root,registry,templates,templateRegistry,fallback,true);
    }
    /** The original source is immutable for this process. Check its file set and
     * identities before/after saves, reusing the content SHA verified at startup.
     * Server writes go to native overlays, never these source files. */
    static String regionsMetadata(Path root,SceneRegistry registry,Path templates,SceneRegistry templateRegistry,Integer fallback)throws IOException{
        return regions(root,registry,templates,templateRegistry,fallback,false);
    }
    private static String regions(Path root,SceneRegistry registry,Path templates,SceneRegistry templateRegistry,Integer fallback,boolean contents)throws IOException{
        if(Thread.currentThread().getName().equals("world-tick")||Thread.currentThread().getName().equals("native-game-owner"))throw new IllegalStateException("checkpoint_source_io_on_owner");
        MessageDigest out=digest();text(out,contents?"szwc-base-v1":"szwc-base-metadata-v1");text(out,fallback==null?"blocked":"flat-native-v1:"+fallback);map(out,root,registry==null?"disabled":registry.checkpointIdentity(),contents);
        text(out,"templates");if(root.equals(templates)){text(out,"same-map");text(out,templateRegistry==null?"disabled":templateRegistry.checkpointIdentity());}else map(out,templates,templateRegistry==null?"disabled":templateRegistry.checkpointIdentity(),contents);return HexFormat.of().formatHex(out.digest());
    }
}
