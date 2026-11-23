package bscp.next;
import com.google.gson.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import szserver.net.*;

/** Production LocalAccounts, transformer, durable tokens and gateway grants; synthetic accounts only. */
public final class NativeRouteOriginTest {
    static String accountLogin;
    static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    static JsonObject call(String method,String path,String body)throws Exception{
        if(method.equals("POST"))body+=(body.isEmpty()?"":"&")+"login="+accountLogin;
        else path+=(path.contains("?")?"&":"?")+"login="+accountLogin;
        var request=RouteOriginTest.signed(method,path,body);var response=RouteOriginTest.send(request,body);
        if(response.statusCode()!=200)throw new AssertionError(response.statusCode()+" "+response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();if(Files.exists(root))throw new IllegalArgumentException("new owned fixture required");Files.createDirectories(root);
        var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));RouteOriginTest.keys=generator.generateKeyPair();
        String login="bscp-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RouteOriginTest.keys.getPublic().getEncoded()));
        accountLogin=login;
        var routes=new ConnectionRoutes("127.0.0.1",List.of("127.0.0.1"),30101,30102);
        try(var accounts=new LocalAccounts(0,routes,root.resolve("accounts.json"))){
            RouteOriginTest.port=accounts.port();String session=call("POST","/session/game","login="+login).get("session").getAsString();
            call("POST","/characters/create","session="+encode(session)+"&character=RouteProbe");
            String startupAlias="bscp-start-"+"ab".repeat(32);
            if(!call("POST","/session/game","nativeSessionAlias="+startupAlias).get("session").getAsString().equals(session))throw new AssertionError("startup alias rotated the durable session");
            var aliasToken=call("POST","/security/issue_auth_token","session="+encode(session)+"&username=RouteProbe").get("token").getAsString();
            UUID aliasProtocol=UUID.randomUUID();var aliasGrant=accounts.gateway(aliasProtocol,login,"RouteProbe",startupAlias,aliasToken);
            if(!accounts.claim(aliasProtocol,startupAlias).equals(aliasGrant))throw new AssertionError("startup alias did not authenticate gateway and UDP claim");
            try{accounts.claim(UUID.randomUUID(),"bscp-start-"+"cd".repeat(32));throw new AssertionError("unknown startup alias accepted");}catch(IllegalArgumentException expected){}
            int checks=0;
            for(String authority:List.of("192.0.2.10:443","play.example.test:443","[2001:db8::7]:443")){
                String host=ConnectionRoutes.requestHost(authority),route="__bscp_route="+encode(authority);
                var addresses=call("GET","/address_list?"+route,"").getAsJsonArray("addresses");
                if(!addresses.get(0).getAsString().equals(ConnectionRoutes.endpoint(host,30101)))throw new AssertionError("gateway origin mismatch");checks++;
                var issued=call("POST","/security/issue_auth_token","session="+encode(session)+"&username=RouteProbe&"+route);
                var grant=accounts.gateway(UUID.randomUUID(),login,"RouteProbe",session,issued.get("token").getAsString());
                if(!grant.worldHost().equals(host))throw new AssertionError("world UDP redirect origin mismatch");checks++;
            }
            System.out.println("PASS "+checks+" native backend address-list and gateway/UDP grant checks; no public host configured");
        }
    }
}
