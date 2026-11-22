package szserver.net;

import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Repeated native HTTP authentication, gateway isolation and cold one-shot ticket recovery. */
public final class AuthenticationStabilityTests {
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static JsonObject post(LocalAccounts accounts,String path,String...values)throws Exception {
        List<String> fields=new ArrayList<>();for(int n=0;n<values.length;n+=2)fields.add(URLEncoder.encode(values[n],StandardCharsets.UTF_8)+"="+URLEncoder.encode(values[n+1],StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+accounts.port()+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(String.join("&",fields))).build();
        var response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new AssertionError(path+": "+response.statusCode()+" "+response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private static String token(LocalAccounts accounts,String login,String name)throws Exception{return post(accounts,"/security/issue_auth_token","login",login,"username",name).get("token").getAsString();}
    private static void rejected(Runnable action,String expected){try{action.run();throw new AssertionError("accepted "+expected);}catch(IllegalArgumentException error){check(expected.equals(error.getMessage()),"wrong rejection: "+error.getMessage());}}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);Path file=Files.createTempDirectory(root,"authentication-").resolve("accounts.json");
        List<String> logins=List.of("AccountASCII","Игрок с пробелом","OtherAccount");
        List<String> names=List.of("DifferentCharacter","Персонаж","X".repeat(24));
        Map<String,String> sessions=new HashMap<>();Map<String,UUID> ids=new HashMap<>();UUID sharedProtocol=UUID.randomUUID();
        LocalAccounts.Grant restored;String restoredToken,restoredSession;
        try(var accounts=new LocalAccounts(0,0,file)) {
            for(int n=0;n<logins.size();n++){
                String login=logins.get(n),name=names.get(n),session=post(accounts,"/session/game","login",login).get("session").getAsString();sessions.put(login,session);
                ids.put(name,UUID.fromString(post(accounts,"/characters/create","login",login,"session",session,"character",name).get("id").getAsString()));
                check(post(accounts,"/session/game","login",login).get("session").getAsString().equals(session),"HTTP session stable");
            }
            for(int round=0;round<24;round++)for(int n=0;n<logins.size();n++){
                String login=logins.get(n),name=names.get(n),session=sessions.get(login),token=token(accounts,login,name);
                rejected(()->accounts.gateway(sharedProtocol,login,name,"invalid-session",token),"gateway_game_session_mismatch");
                rejected(()->accounts.gateway(sharedProtocol,"unknown-account",name,session,token),"gateway_identity_mismatch");
                var grant=accounts.gateway(sharedProtocol,login,name,session,token);
                check(grant.characterId().equals(ids.get(name)),"selected character UUID");
                check(accounts.gateway(sharedProtocol,login,name,session,token).equals(grant),"same-token gateway retry");
                check(accounts.claim(sharedProtocol,session).equals(grant),"world consumes exact character");
                rejected(()->accounts.claim(sharedProtocol,session),"world_auth_failed");
            }
            // Independently issued tokens for two clients must not consume one another's receipts.
            String login=logins.get(0),name=names.get(0),session=sessions.get(login);
            UUID firstProtocol=UUID.randomUUID(),secondProtocol=UUID.randomUUID();String first=token(accounts,login,name),second=token(accounts,login,name);
            var pool=Executors.newFixedThreadPool(2);
            try{
                var a=pool.submit(()->accounts.gateway(firstProtocol,login,name,session,first));
                var b=pool.submit(()->accounts.gateway(secondProtocol,login,name,session,second));
                check(a.get(10,TimeUnit.SECONDS).characterId().equals(ids.get(name))&&b.get(10,TimeUnit.SECONDS).characterId().equals(ids.get(name)),"concurrent native protocols retain identity");
                var firstClaim=accounts.claim(firstProtocol,session);var secondClaim=accounts.claim(secondProtocol,session);
                check(firstClaim.characterId().equals(ids.get(name))&&secondClaim.characterId().equals(ids.get(name))&&firstClaim.protocolId().equals(firstProtocol)&&secondClaim.protocolId().equals(secondProtocol),"independent world claims");
            }finally{pool.shutdownNow();}
            restoredSession=sessions.get(logins.get(1));restoredToken=token(accounts,logins.get(1),names.get(1));
            restored=accounts.gateway(sharedProtocol,logins.get(1),names.get(1),restoredSession,restoredToken);
        }
        try(var recovered=new LocalAccounts(0,0,file)){
            check(post(recovered,"/session/game","login",logins.get(1)).get("session").getAsString().equals(restoredSession),"cold HTTP session unchanged");
            check(recovered.gateway(sharedProtocol,logins.get(1),names.get(1),restoredSession,restoredToken).equals(restored),"cold gateway retry retains UUID");
            check(recovered.claim(sharedProtocol,restoredSession).equals(restored),"cold ticket consumed once");
            rejected(()->recovered.claim(sharedProtocol,restoredSession),"world_auth_failed");
        }
        System.out.println("Authentication stability: "+checks+" PASS (72 full HTTP/gateway/world cycles, Unicode, concurrent protocols and cold recovery)");
    }
}
