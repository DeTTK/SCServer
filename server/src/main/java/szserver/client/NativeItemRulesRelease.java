package szserver.client;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reviewed ABI for the original item predicates, metadata and banks. */
final class NativeItemRulesRelease {
    private static final Map<String,String> HASHES=Map.ofEntries(
        Map.entry("1950979903","6dded54216e2f674ee74f41271f9240036ca5c7ad15664a188bc554e7afefe10"),
        Map.entry("1271395251","e81382d26f6056cdf55262cfbd951dd8990828e8f5f6052d0a2451a874273d1e"),
        Map.entry("949164803","ca90c161bffd2445cb6c7254af0766ca876f65c21db5c2e2e5a552e28fc7d5fe"),
        Map.entry("1799496656","32caf0e75c8a694996dfb1b14e0ea6b92d25cc10d342db25e20642f18abaed9c"),
        Map.entry("942610364","6f5de6964d417504799025b08ca71863f245c18d9cd607d3cabe559ec80fff9b"),
        Map.entry("725248314","c8852d05dd1b2263a22aaa027b63d38663bcb9be44349e9e95e03b88e6981322"),
        Map.entry("1750946572","61f81fde44c6c93accb7c1ea2a2fe0110380b3f74b836804460b22d128409470"),
        Map.entry("1441854005","29c322b8c281e16ff3352d7328065ec6637f2edfe6b168b07548912a5b5af44b"),
        Map.entry("1579742958","02a68ac14adb0dc53591038fd5d6d4eb5b0adcbbc9b8c56ee8d3b5375b61e5b9"),
        Map.entry("702433834","e4edd23ed833fa1387b705e8eb928b80aaaa7561c66e2eae2f6fce4651ec620e"),
        Map.entry("59871510","ff1a8ebb67fd7f458485a9e46a39d92913469664097e2dd51534cac85dcdf7a1"),
        Map.entry("830745898","8f504145c19de5f4f3c0c616ed3483ee823e066e43199d14494bd8fb3ce75e8f"),
        Map.entry("1824362651","ad54b8ce5f5908fa73972d219c95e8dd12dee6cce0888deb62096dc749d5edbc"),
        Map.entry("61438557","0eb8f1b621204e59cf40de2efea3378956df63e5bcb913701ac1f72d3f0ae27f"),
        Map.entry("1745367406","d456665e126411fd18ef84489b4bc2e69bd243f6bfcb92e6e2e3f6d7eb4927ae"),
        Map.entry("1967140906","4d1dec0909668a17beadeb299305020f6291c4ac1c8a4698d7d49ac56f84a25c"),
        Map.entry("1042667109","e0d7fd81ff38c22d0d38dd17b8b35122691738315d89c8efc06995c74b104fb7"),
        Map.entry("62393481","1d303c830b73c78f591271ab33c4129650c28cf3874647107dde7d4a0567254f"),
        Map.entry("1366800141","94f14ec8c4ac992b20763969bae2b6ee2bb151ca9eb16e79fac10b46c6453dba"),
        Map.entry("211097523","4a9a19a8416399a0a331d3f9ac430c44c00609c9cdf1a503f0d2649c7b12194a"));
    static void verify(Path runtime)throws Exception{for(var entry:HASHES.entrySet()){String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime.resolve("classes").resolve(entry.getKey()+".class"))));if(!actual.equals(entry.getValue()))throw new IOException("native_item_rules_release_changed:"+entry.getKey());}}
    private NativeItemRulesRelease(){}
}
