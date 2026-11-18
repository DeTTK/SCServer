package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Installed scene identity, input admission and complete cable handler release. */
final class ActorZiplineRelease {
    private static final Map<String,String> HASHES=Map.ofEntries(
        Map.entry("1600012472","5d39544fd4ced059a619acc4908e06c2b34c11356b1eeae6989b9162c58f2bb9"),
        Map.entry("930637873","e57a7b1d3fdd1c72fab60f8a7741e920c7a19b3fa25ff29bfb2249d921746ce2"),
        Map.entry("789923706","1932905e57c7d3580bcfda78eed46288485b5cc6ad5da48f9cd75bc58e27a13a"),
        Map.entry("1122096288","f7ed2889b02404cb05bb65d880dd62fda4702678bfb74e0d4207a6f1fff3b6cb"),
        Map.entry("829855000","6a27132130264232cd1a204d04200fb4ea5a0eef3d64713aaa2c515179fbc14e"),
        Map.entry("973439283","1724a51c36e8e154a68c5a18f0551b99b99efb6f74360b10021132f5bbd70b4d"),
        Map.entry("1353129168","cd5bc6d46d7941c86bf7125b2c19607d7b1321aecaef880f12912e06b87f1e1c"),
        Map.entry("861754670","9cc087e9e69da4640fbadac7c5fa39d104cee05780ae932b6016894034cb7c01"),
        Map.entry("296780051","2328650943ad1680d5990e2dd678f98a544cc266d63f6f91653f0c9a2b62c937"),
        Map.entry("228081277","83379d8d4766c23282e5622c98e791c5c08afea47e58d0c959f5e2771a373e19"),
        Map.entry("1157611462","baaef0404aa81cbe6f27d38c42b4fa48c6f0f63dbe04d2c30dfe0df309fb0d87"),
        Map.entry("485261932","4e78b04bda7437c0f3ca276e494ff599fcc9836cf5df2ccfe16316a12c55f9b5"),
        Map.entry("494043341","5bed541edb5306bc2fd5123c12de97649dd562994d10efba223a4c3bb34d9e69"),
        Map.entry("997295515","5635f287f335e1fad4617e995a5d3e223fe0978017efb016b23b2fbf97a3f671"),
        Map.entry("1827030006","e06cb9847b6c155bccd07365cd2db54ad6e8d323515a6cc5921c70bf2b882e9c"),
        Map.entry("1814125595","3f00da14ae31c9922f7ca0f699dede3a0d17741c0904b356ecb96ab70ff2f24c"),
        Map.entry("1886271463","fbafa562ccfc06f8586361f3746c40c75042261f52773f9cca55f7ff9df79adc"),
        Map.entry("447252483","4cd8d5e874e96415b6e539a383c58e92f73d532d407421b7316976315cda924b"),
        Map.entry("1422486348","47d7c76d8460307ab40ad1a7256178cb3e74699ebd8186338bcbb2c40b5ad908"),
        Map.entry("1630548737","e1149ec99d322193edfe7dc5054d6afb82c51efcb055610adc8054c9d8d664c1"),
        Map.entry("512103080","02e33df80aa77c6e693389d0973c9cb84be239b6635eaa850e7a244a8d00270c"),
        Map.entry("1205530968","6803361a0f19627f91b4d2651e3678e846016617ae5368ddb40e98216283a9eb"),
        Map.entry("1507399958","d53c3d8ddccb128dbd9d11af5db39c5f115aeb25495992bda9785e0aa00b81bf"),
        Map.entry("578131936","c58779bff7b808b82b5750ccf24131d093a47e5cc51df781cd62829f1fb64cb9"),
        Map.entry("873944585","f4843893b0326bef628a0ec927ff7b4bfd3605c12417727f67729a7644246e1c"),
        Map.entry("1046176253","7f85ef4c47654d79b7821d428542f3f745fa67884e90e10a580d1a1c3ebe09cb")
    );
    static void verify(Path runtime)throws Exception{for(var e:HASHES.entrySet())if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime.resolve("classes").resolve(e.getKey()+".class")))).equals(e.getValue()))throw new IllegalStateException("actor_zipline_release_mismatch:"+e.getKey());}
}
