package szserver.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Map registries declare AABB names that differ from shipped files only by case.
 * Windows ignores that, Linux reports a missing file, so the declared name must still resolve. */
public final class CollisionCaseTests {
    private static int checks;
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
    private static byte[] asset() {
        ByteBuffer out=ByteBuffer.allocate(8+2+24).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte)'A').put((byte)'A').put((byte)'B').put((byte)'B').putInt(1);
        out.putShort((short)0);
        out.putFloat(-1).putFloat(-1).putFloat(-1).putFloat(1).putFloat(1).putFloat(1);
        return out.array();
    }
    private static SceneRegistry.Definition definition(String aabb) {
        com.google.gson.JsonObject json=new com.google.gson.JsonObject();
        json.addProperty("aabb",aabb);json.addProperty("collision","solid");
        return new SceneRegistry.Definition(1,"bochka","box","profile",json);
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("szr-collision-case-");
        try {
            Path asset=root.resolve("assets/objects/Bochka_group1.aabb");
            Files.createDirectories(asset.getParent());
            Files.write(asset,asset());
            // Windows and macOS resolve a differing case without help, so the fallback must stay unused
            // there. Only a case-sensitive filesystem needs the index, and only there is it counted.
            boolean caseSensitive=!Files.exists(root.resolve("assets/objects/bochka_group1.aabb"));
            CollisionBoxes boxes=new CollisionBoxes(root);
            check(boxes.get(definition("objects/Bochka_group1.aabb")).size()==1,"exact case resolves");
            check(boxes.get(definition("objects/bochka_group1.aabb")).size()==1,"declared lower-case name resolves");
            check(boxes.get(definition("objects/BOCHKA_GROUP1.AABB")).size()==1,"any declared case resolves");
            check(boxes.get(definition("objects:bochka_group1.aabb")).size()==1,"colon separator resolves");
            String fallbacks=caseSensitive?"3":"0";
            check(String.valueOf(boxes.metrics().get("caseFallbacks")).equals(fallbacks),
                (caseSensitive?"case-sensitive host falls back only on mismatch":"case-insensitive host needs no fallback")
                +": "+boxes.metrics().get("caseFallbacks"));
            try { boxes.get(definition("objects/missing.aabb"));throw new AssertionError("missing asset accepted"); }
            catch(java.nio.file.NoSuchFileException expected){checks++;}

            // Exercise the fallback itself on Windows too, where ordinary lookup ignores case.
            Files.write(root.resolve("assets/objects/texture.png"),new byte[0]);
            var fallback=CollisionBoxes.class.getDeclaredMethod("caseInsensitive",Path.class,String.class);fallback.setAccessible(true);
            check(fallback.invoke(boxes,root.resolve("assets"),"objects/bochka_group1.aabb").equals(asset),"direct fallback returns the declared AABB");
            check(((Number)boxes.metrics().get("caseIndexEntries")).intValue()==1,"textures excluded from index");
            check(((Number)boxes.metrics().get("caseIndexBytes")).longValue()<16L*1024*1024,"index has an explicit memory budget");
            // Ambiguous folded names cannot silently select random geometry on Linux.
            Path upper=root.resolve("assets/objects/BOCHKA_GROUP1.aabb");
            if(caseSensitive){Files.write(upper,asset());var ambiguous=new CollisionBoxes(root);try{fallback.invoke(ambiguous,root.resolve("assets"),"objects/bOcHkA_group1.aabb");throw new AssertionError("ambiguous asset accepted");}catch(java.lang.reflect.InvocationTargetException expected){check(expected.getCause() instanceof java.io.IOException,"ambiguous case rejected");}Files.delete(upper);}
            Path outside=Files.createTempDirectory("szr-collision-outside-");
            Files.write(outside.resolve("escaped.aabb"),asset());
            Path link=root.resolve("assets/objects/linked.aabb");
            boolean linked;
            try { Files.createSymbolicLink(link,outside.resolve("escaped.aabb"));linked=true; }
            catch(UnsupportedOperationException|java.io.IOException refused){linked=false;}
            if(linked) {
                try { boxes.get(definition("objects/linked.aabb"));throw new AssertionError("asset symlink accepted"); }
                catch(IllegalArgumentException|java.nio.file.NoSuchFileException expected){check(true,"asset symlink leaving the map root is rejected");}
            }
            try(var walk=Files.walk(outside)){for(Path p:walk.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}

            try { boxes.get(definition("../../etc/passwd"));throw new AssertionError("traversal accepted"); }
            catch(IllegalArgumentException|java.nio.file.NoSuchFileException expected){checks++;}
        } finally {
            Path assets=root.resolve("assets");
            if(Files.exists(assets))try(var walk=Files.walk(assets)){for(Path p:walk.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
            Files.deleteIfExists(root);
        }
        System.out.println("PASS "+checks+" declared AABB case resolution checks");
    }
}
