package szserver.client;
import java.nio.file.*;import java.util.*;import java.security.*;
/** Canonical factory constructor defaults needed to restore existing native inventories. */
final class NativeInventoryCatalogRelease {
 static final Map<Integer,String> BLOCK_FACTORIES=Map.ofEntries(Map.entry(1529,"973018104"), Map.entry(3118,"287510043"), Map.entry(3119,"973018104"), Map.entry(3140,"973018104"), Map.entry(3877,"973018104"), Map.entry(3928,"973018104"), Map.entry(4012,"973018104"), Map.entry(4025,"973018104"), Map.entry(4026,"973018104"), Map.entry(4095,"973018104"));
 private static final Map<String,String> HASHES=Map.ofEntries(Map.entry("1366800141","94f14ec8c4ac992b20763969bae2b6ee2bb151ca9eb16e79fac10b46c6453dba"),
Map.entry("973018104","d99f481945f24bb05d6005b4153bdae6d38fe639685fbffb0c70d90c11cf262a"),
Map.entry("287510043","546fcbb925c3a68d47f1fc5b7dca45b96b9915797741b93830f2859bd1aee90b"),
Map.entry("895541180","bd180b1fd6157ab982dea7cbb824ec3ba13fd037899d842ec4e3ab8835438771"),
Map.entry("1078890796","eefb20c5f36537b53b0eff08223d8fb59c62c2aa161e65fa6e65851aaa4b1f88"),
Map.entry("1989703492","9ae65ef72f896bb88a3e15272194df30f957b60e6073410faa560fb5d85fa2ad"),
Map.entry("1367608400","7b43c558ee3c234c0b2d188dcb7a78bed5ea51b25dfb5e6bdc0d273f2caa94df"),
Map.entry("1376187458","9cd6941a8d07cdad510da34b227e9eb819b6a2e34d840369aeebd4fc486bd8bf"),
Map.entry("1632946731","83328f45880417b22a66ef66d5d629a32e9842dfe633d4e247002a62dc449529"),
Map.entry("850786143","6a94498e82efc0f6c863396cf75e62290218d2c94f0a9f314764228f84b0426f"),
Map.entry("207310501","fce12358daf5a0f71f2ed13f4149ea3bc1f37fc114fc6fd0dab34ac5c28f0403"),
Map.entry("1824362651","ad54b8ce5f5908fa73972d219c95e8dd12dee6cce0888deb62096dc749d5edbc"),
Map.entry("211097523","4a9a19a8416399a0a331d3f9ac430c44c00609c9cdf1a503f0d2649c7b12194a"));
 static void verifyRuntime(Path runtime)throws Exception{for(var e:HASHES.entrySet()){String h=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(e.getKey()+".class"))));if(!h.equals(e.getValue()))throw new IllegalStateException("native_inventory_catalog_release_changed:"+e.getKey());}}
 private NativeInventoryCatalogRelease(){}
}
