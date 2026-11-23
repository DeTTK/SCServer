package szserver.inventory;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class NativeItemMetadataTests {
    private static int checks;
    private static byte[] data(String digest,String rows){return ("{\"version\":1,\"definitionsSha256\":\""+digest+"\",\"rows\":"+rows+"}").getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static void reject(byte[] data,String digest)throws Exception{boolean rejected=false;try{NativeItemMetadata.parse(data,digest);}catch(Exception expected){rejected=true;}check(rejected,"invalid snapshot rejected");}
    public static void main(String[] args)throws Exception{
        var rows=NativeItemMetadata.parse(data("release","{\"42\":{\"width\":3,\"height\":2,\"limit\":64}}"),"release");check(rows.get(42).equals(new NativeItemMetadata.Row(3,2,64)),"genuine geometry and stack limit survive export");
        reject(data("old","{}"),"new");
        for(String row:List.of("{\"width\":0,\"height\":2,\"limit\":64}","{\"width\":3,\"height\":257,\"limit\":64}","{\"width\":3,\"height\":2,\"limit\":0}","{\"width\":1.5,\"height\":2,\"limit\":64}"))reject(data("release","{\"42\":"+row+"}"),"release");
        reject(data("release","{\"70000\":{\"width\":1,\"height\":1,\"limit\":1}}"),"release");
        check(NativeItemMetadata.parse(data("release","{\"42\":{\"width\":1,\"height\":1,\"limit\":100000}}"),"release").get(42).limit()==100000,"native high counts are not clamped to resource guesses");
        System.out.println("PASS native item snapshot "+checks+" compatibility and validation checks");
    }
}
