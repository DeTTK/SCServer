package szserver.client;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Reviewed standalone native constructors and immutable trait release. */
final class NativeDeveloperItemsRelease {
    static final Map<Integer,String> FACTORIES=Map.ofEntries(
        Map.entry(5401,"56524039"),
        Map.entry(5402,"555856864"),
        Map.entry(5403,"712367874"),
        Map.entry(5405,"1559253930"),
        Map.entry(5406,"521898846"),
        Map.entry(14968,"1892488504"),
        Map.entry(14969,"1575972505"),
        Map.entry(15789,"1361838270"),
        Map.entry(15791,"217388488"),
        Map.entry(16504,"764476749"),
        Map.entry(16968,"263346210"),
        Map.entry(26972,"1016686565"),
        Map.entry(26980,"117395543"),
        Map.entry(26982,"48542403"),
        Map.entry(26983,"1908574730"),
        Map.entry(26984,"293659639"));
    private static final Map<String,String> HASHES=Map.ofEntries(
        Map.entry("1016686565","26b4002158b09ef44d836b22abfe72ae1dc4fc17db9e9d221e4549b060a35ce4"),
        Map.entry("1074092844","154857f5b3bc5ec14ac2f923f4237d43b038b70f53584b1e8c97ebbcac9755ee"),
        Map.entry("10835682","351224d9b39f67e40d18f30ba67dd31be0c8ee5a4a9048f6f36ab6b478034601"),
        Map.entry("117395543","d09640dd201b838d68ff3129defca03e321ac90ff50d25020ad1ab7e69683e23"),
        Map.entry("1254743678","0647f5b0f2747314f965021c4d4ec7491e468a21deaf5fef340f27b7d002a9bc"),
        Map.entry("1361838270","56517bb497fef7c8e259483d5d390bcdbf2c7a4bbc7770cc948c2ef0328a6074"),
        Map.entry("1366800141","94f14ec8c4ac992b20763969bae2b6ee2bb151ca9eb16e79fac10b46c6453dba"),
        Map.entry("1544733370","1e7185261913dbbdfbc09a4727c30c1af4aaf3ae9027dbee701b76c44e4e2e6d"),
        Map.entry("154675812","a6b8b0781cc3c70458df45b8e3892676c512c496daf89f2fc88113b76751ca0e"),
        Map.entry("1559253930","27c6e9f76a83cf759e213609da2230eb524d2a699fa85e3c03c5b802475a435b"),
        Map.entry("1575972505","50d74f3a6762ead9c0d17c6ff247c1e05e4addd57583a2618d48a4b874c0541e"),
        Map.entry("1824362651","ad54b8ce5f5908fa73972d219c95e8dd12dee6cce0888deb62096dc749d5edbc"),
        Map.entry("1892488504","6ed16280175b2684f85b1e8b7beb32df1bdb2d63c64a4eb6701d563c30279018"),
        Map.entry("1908574730","852d20feac35e4cd085944731aa885c95559e21c110142868d6ac1dade00b530"),
        Map.entry("211097523","4a9a19a8416399a0a331d3f9ac430c44c00609c9cdf1a503f0d2649c7b12194a"),
        Map.entry("217388488","94b8cf5b72360322d9793377832ec692a4cb01b63bae02626d60d0be33d530d6"),
        Map.entry("263346210","7a360810c66d5a33464b38074b1ba943e6dfd272f78ef4b6b858474df0d7bab4"),
        Map.entry("293659639","ff70e0483ffb59f501d01bb610e8393554f3ec499304ef518cfc2cb3dfa0a1b8"),
        Map.entry("325849835","7c08e0f5eea36851bec6fbbdada9e41380f2af7f565079e3208495b527a5fe9a"),
        Map.entry("416829547","0337090c3fb25066b7926b30026c6903748fed9ecacb9e48984d69aa7840e532"),
        Map.entry("48542403","07483f65bb85150bc7df2f7e8103e910e8a6e0307d2b8d06db2d648eb90e0675"),
        Map.entry("521898846","f2edc9e163031195321fecdd7c384186f535eeb7a4f20150eaaa0c25f26534ff"),
        Map.entry("555856864","783e7aa283007f08bf00065983d0940d9fac40be377b9698db64757ddaf0b1ab"),
        Map.entry("56524039","1276fd45bbd04a0243e4ea3c77e29b6263660c083cafb3219fd56ea294eaa9ce"),
        Map.entry("566165616","95ac495f3d30826f999b82a36186a911989a7f9cda28beb05732e8f2fca45a95"),
        Map.entry("58006653","fd64415a9bbb69216ee7c3619ccf2968807d55f9bdf8f7d50161fca475b3b600"),
        Map.entry("712367874","1151e5113874e1d1524d96e558326e7cf54564e38d48baf6456976e2e2716582"),
        Map.entry("764476749","903478db2ebe4dc7ac3bc0f918511abd90c5dc1f3e3bd24cf07c1a8e6fa8fc31"),
        Map.entry("810711645","c1a1bab9445870d6ad293b359b772fc06b2858fb7e707c2a04d7e6e7743540da"),
        Map.entry("815517369","441980294bf71513b7252232280a37e76e52f193a0cc045f534cfddfd84d78f2"),
        Map.entry("895541180","bd180b1fd6157ab982dea7cbb824ec3ba13fd037899d842ec4e3ab8835438771"));
    private static String sha(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static void verifyRuntime(Path runtime)throws Exception{for(var row:HASHES.entrySet())if(!sha(runtime.resolve("classes").resolve(row.getKey()+".class")).equals(row.getValue()))throw new IllegalStateException("native_developer_item_release_changed:"+row.getKey());}
    static void verifyDefinitions(Path fixtures)throws Exception{if(!sha(fixtures.resolve("native-developer-item-definitions.json")).equals("9c6ca78f12b7b9578b20c94f613c7b28d3c01e76cb6f6545cfb45fd4434fbadb"))throw new IllegalStateException("native_developer_item_definitions_changed");}
    private NativeDeveloperItemsRelease(){}
}
