package szserver.client;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reviewed ABI for the original item predicates, metadata and banks. */
final class NativeItemRulesRelease {
    private static final Map<String,String> HASHES=Map.ofEntries(
        Map.entry("1309545617","3c48d1f6bc9c880310a634e3c45eb1f81a2a7cce710828f43edbe54a7f2e16d5"),
        Map.entry("1491633278","13e409301a7cc623162018341619544f785f1fc084281a451a7aa473ba9ffc69"),
        Map.entry("423821855","dc0d9103b92cc1e8dc3a47008117db212df8632bce3982ae3fba75b89e1836e5"),
        Map.entry("669790692","0f028ab7c7416262b28b61d0e09e341c72d9fb7721a20476ee553e49ff7c2e70"),
        Map.entry("965067844","1b35e2abdc0994ea1e313bb2b32dd01b2c31507365f815e7e201503643d34959"),
        Map.entry("1756593341","7186180dad99f41de9e202c4a216b3a813ccd0b19101a5d81a0ebf80c2f41268"),
        Map.entry("220710426","f5a8141502cbded6ad7f9878d6fea7a8aff5a61db50722b369f77748a7b64794"),
        Map.entry("1464336358","915e6d650c7ca6e81cc50710b1c97e9aa6a594697f6e63690cc528393d0d3e4f"),
        Map.entry("98008865","f2c4f061d0260031d194cb1aecd60b8193da39afc5c560ffabde1cbd78da27d7"),
        Map.entry("724465038","3b47a0354ddf435f1d5d37413f9bcc1e9ae08a7799a91687ad83e40acc7d4cef"),
        Map.entry("1553799403","ff4345b7f55404f6ccd0235b483d8ec381cc60b3e190a0e3a30c51f94147ba43"),
        Map.entry("1281655698","6355104be7405660f6dd673656eac6098bd8b1d790864159fac868ba2734f169"),
        Map.entry("702668214","7882caaa8c7388067578938f3ade9d4317f0ce537fcde4cadbe870777bdcbc47"),
        Map.entry("1033600170","0a8bb6eb21973bafba8586ebaa7d89d02b450a87ec219e5158f37455e7a60bf5"),
        Map.entry("959598215","ecd152fd60d1d47605f55b77fd5b6a045b114c7ba29309304773dc8e0641c7bb"),
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
    static void verify(Path runtime)throws Exception{for(var entry:HASHES.entrySet()){String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(entry.getKey()+".class"))));if(!actual.equals(entry.getValue()))throw new IOException("native_item_rules_release_changed:"+entry.getKey());}}
    private NativeItemRulesRelease(){}
}
