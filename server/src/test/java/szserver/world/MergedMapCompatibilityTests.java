package szserver.world;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;

/** Synthetic dense chunks and per-map codec identity; no private game assets. */
public final class MergedMapCompatibilityTests {
    private static String digest(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    public static void main(String[] args)throws Exception {
        int count=9000;
        ByteBuffer b=ByteBuffer.allocate(4+20*count).putInt(count);
        for(int i=0;i<count;i++)b.put((byte)0).putInt(i%16).putInt(i/256).putInt(i/16%16).put((byte)0).putShort((short)0).putInt(0);
        if(TilePayload.decode(b.array(),0,0,0).size()!=count)throw new AssertionError("dense chunk");
        if(TilePayload.MAX_TILES!=65536||SceneCatalog.MAX_IDENTITIES!=2_000_000)throw new AssertionError("bounded map limits");
        try{TilePayload.decode(ByteBuffer.allocate(4).putInt(65537).array(),0,0,0);throw new AssertionError("tile limit");}catch(IllegalArgumentException expected){}
        Path tmp=Files.createTempDirectory("merged-map-contract-");
        try {
            Path map=tmp.resolve("map"),old=tmp.resolve("old");Files.createDirectories(map.resolve("region"));Files.createDirectories(old.resolve("region"));
            String registry="{\"format\":\"szserver.stalcraft-polymorphic-registry\",\"version\":1,\"build\":\"test\",\"entries\":[]}";
            Files.writeString(map.resolve("registry.json"),registry);Files.writeString(old.resolve("registry.json"),registry);
            Path global=tmp.resolve("codecs.json"),local=map.resolve("object-codecs.json");
            String codecs="{\"version\":1,\"target_build\":\"test\",\"entries\":[]}";Files.writeString(global,codecs);
            SceneRegistry legacy=new SceneRegistry(old,map.resolve("registry.json"),global);
            if(!legacy.checkpointIdentity().endsWith(digest(global)))throw new AssertionError("legacy fallback");
            Files.writeString(local,codecs+"\n");
            for(Path path:List.of(map,map.resolve("region")))if(!new SceneRegistry(path,map.resolve("registry.json"),global).checkpointIdentity().endsWith(digest(local)))throw new AssertionError("map-local codecs");
            if(!legacy.forMap(map).checkpointIdentity().endsWith(digest(local)))throw new AssertionError("map switch codecs");
            if(!legacy.checkpointIdentity().endsWith(digest(global)))throw new AssertionError("existing map changed");
            Files.writeString(local,codecs.replace("test","wrong"));
            try{new SceneRegistry(map,map.resolve("registry.json"),global);throw new AssertionError("wrong local codec accepted");}catch(java.io.IOException expected){}
            System.out.println("MergedMapCompatibilityTests PASS");
        }finally{try(var files=Files.walk(tmp)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
