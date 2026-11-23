package szserver.client;

import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Compiled canonical palette and immutable native-trait release; no runtime edits. */
final class NativeBlockItemsRelease {
    static final String DEFINITIONS_SHA="df8b87619261d7dc93cc5b79949f212492de5d57b8ba29dacc8d440bf12ede64";
    private static final Map<String,String> HASHES=Map.ofEntries(
        Map.entry("1219622435","8164665e2def11ca720c9c47837c58286b5b127a9fee4d55397bbc3dc6f2395b"),
        Map.entry("1310780999","f6a75ed4b2e5199d94ad60e13754200c3fe62f54d714119811f4e0cc866b3a19"),
        Map.entry("1366800141","94f14ec8c4ac992b20763969bae2b6ee2bb151ca9eb16e79fac10b46c6453dba"),
        Map.entry("154675812","a6b8b0781cc3c70458df45b8e3892676c512c496daf89f2fc88113b76751ca0e"),
        Map.entry("1648977992","1bdb2b189260780b684bbf9a11ec3746c2ed6299bec44f694f1a4773aba6853d"),
        Map.entry("1824362651","ad54b8ce5f5908fa73972d219c95e8dd12dee6cce0888deb62096dc749d5edbc"),
        Map.entry("1872807016","08ccd515b0f044d9b5673d9cc05383a38c345b5a7d0cb8f8c1c400d3b8618ab6"),
        Map.entry("1959919792","3864d45f6a01e66ede43d160d0fb65a93e3c67dc3265e22a4c6ecd46a45c1e2c"),
        Map.entry("211097523","4a9a19a8416399a0a331d3f9ac430c44c00609c9cdf1a503f0d2649c7b12194a"),
        Map.entry("308143588","ffbba10b8bab76dbe961b1f960b8af1b9a8e9772fe8b630e5be2662496d75cc4"),
        Map.entry("436788988","404b99a781350ed573b77a21e1d3f3363d119d6557e365a33e1fb29e3f7fb417"),
        Map.entry("559207413","7cf3f60cfd69673f12a7e430e1b168c08f260d59e7b450e0264bdf99ba1f6018"),
        Map.entry("681259278","2cc0ea77c47bef5dcd8f2594cec441a5d96ef89b0287074ebf512ef38f6293b4"),
        Map.entry("683052146","bd36d5c01ba15a4f65512064e82f3debd6c19596d6188cab3887d2f866787238"),
        Map.entry("888622828","5bf5068dfb51c78db59edc7564e395de71f1d46cad84ddff83640331e19f4414"),
        Map.entry("973018104","d99f481945f24bb05d6005b4153bdae6d38fe639685fbffb0c70d90c11cf262a"),
        Map.entry("981745603","c6934dee309e20b7843755ab70a5fd41ca26d92744ac3385439c3838752c5889"));
    private static String sha(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(path)));}
    static void verifyRuntime(Path runtime)throws Exception{for(var row:HASHES.entrySet())if(!sha(runtime.resolve("classes").resolve(row.getKey()+".class")).equals(row.getValue()))throw new IllegalStateException("native_block_item_release_changed:"+row.getKey());}
    static void verifyDefinitions(Path fixtures)throws Exception{if(!sha(fixtures.resolve("native-block-item-definitions.json")).equals(DEFINITIONS_SHA))throw new IllegalStateException("native_block_item_definitions_changed");}
    private NativeBlockItemsRelease(){}
}
