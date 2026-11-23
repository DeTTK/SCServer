package szserver.client;
import java.nio.file.*;import java.security.*;import java.util.*;
/** Reviewed medicine factories, quick-use rules and GameplayEffects codecs. */
final class NativeMedicineRelease {
    private static final Map<String,String> HASHES=Map.ofEntries(
        Map.entry("1166767938","02d67adedbef661b259173f4f20f8828928c70608c39baa6be7e5a71b057084e"),
        Map.entry("1799496656","32caf0e75c8a694996dfb1b14e0ea6b92d25cc10d342db25e20642f18abaed9c"),
        Map.entry("949164803","ca90c161bffd2445cb6c7254af0766ca876f65c21db5c2e2e5a552e28fc7d5fe"),
        Map.entry("1305711328","fea773aba56a6537c05637ecb040ad36691af97f6b4f4e1d762418d962ef3f5b"),
        Map.entry("1632946731","83328f45880417b22a66ef66d5d629a32e9842dfe633d4e247002a62dc449529"),
        Map.entry("1321132729","376c88d3fa5ad1082c54d01a295ac5c5b92600a9a1890e97ffdba919b8162da8"),
        Map.entry("272027518","190c0a3e85c9382d9c889a9e3ce35e0a504d6a405bc171cbdf9c03d41af9f695"),
        Map.entry("782505764","847bb9544476e5f6a6b0dd5fdaa2ae8dc7f5d023551c8bafe89071f58cf26b1c"),
        Map.entry("1380341640","cb90f02e64a9d1ad561b8d7e1aef7c98471c967b3d1a60fcdbafac904de6bf39"),
        Map.entry("1967986234","09df3d609445f2d928a4a458db493c8fe1aee010f2ece8cef1129bc4f0cfbe38"),
        Map.entry("222945433","3642603f13988661410c07429ae628e4fb06388298de8d3dfc896969c2ca0a20"),
        Map.entry("837608172","e3a51f3bbf0e048e15932f9ec2e8925eba624171522c0dbd0b7599cb1e4ca3eb"),
        Map.entry("626983914","79900fdd7c61415259eaf8a9ceb60b2673b7f327cd39062cc6c95df4104e6133"),
        Map.entry("1819519616","6333c4762b02e0ce3f1e72b93b2956d2aef3cc68c58611d8740cc6f20f54dc24"),
        Map.entry("1419211943","034b5abb70d4e06fa8cc6ed14bd6619c3033b94a893eb7ee5385e149cc75ee92"),
        Map.entry("666192130","ff504c8969ffc7f873fa61a2611f3e4b48f585c8be72ededbbc906bd5f76021d"));
    static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet()){String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(e.getKey()+".class"))));if(!digest.equals(e.getValue()))throw new IllegalStateException("native_medicine_release_changed:"+e.getKey());}}
    private NativeMedicineRelease(){}
}
