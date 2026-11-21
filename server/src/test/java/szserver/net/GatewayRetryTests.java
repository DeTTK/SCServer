package szserver.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.UUID;

/** Exercise real HTTP token issuance and durable gateway/world consumption. */
public final class GatewayRetryTests {
    private static int checks;
    private static JsonObject post(LocalAccounts accounts,String path,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+accounts.port()+path))
            .header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new AssertionError(response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private static String token(LocalAccounts accounts)throws Exception {
        return post(accounts,"/security/issue_auth_token","login=Tester&username=Driver").get("token").getAsString();
    }
    private static void rejected(Runnable action,String expected) {
        try{action.run();throw new AssertionError("accepted "+expected);}
        catch(IllegalArgumentException error){if(!expected.equals(error.getMessage()))throw new AssertionError(error);checks++;}
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        try(var accounts=new LocalAccounts(0,0,root.resolve("accounts.json"))) {
            String session=post(accounts,"/session/game","login=Tester").get("session").getAsString();
            post(accounts,"/characters/create","login=Tester&character=Driver");
            UUID protocol=UUID.randomUUID();String first=token(accounts);
            var grant=accounts.gateway(protocol,"Tester","Driver",session,first);
            if(!grant.equals(accounts.gateway(protocol,"Tester","Driver",session,first)))throw new AssertionError("same-token retry");checks++;
            rejected(()->accounts.gateway(protocol,"Tester","Driver",session,"invalid"),"gateway_token_mismatch");
            post(accounts,"/characters/create","login=Tester&character=Passenger");
            String other=post(accounts,"/security/issue_auth_token","login=Tester&username=Passenger").get("token").getAsString();
            rejected(()->accounts.gateway(protocol,"Tester","Driver",session,other),"gateway_identity_mismatch");
            if(!grant.equals(accounts.gateway(protocol,"Tester","Driver",session,first)))throw new AssertionError("failed retry changed receipt");checks++;
            String replacement=token(accounts);
            if(!grant.equals(accounts.gateway(protocol,"Tester","Driver",session,replacement)))throw new AssertionError("fresh-token retry");checks++;
            rejected(()->accounts.gateway(protocol,"Tester","Driver",session,first),"gateway_token_mismatch");
            rejected(()->accounts.gateway(UUID.randomUUID(),"Tester","Driver",session,replacement),"gateway_token_unknown");
            if(!grant.equals(accounts.claim(protocol,session)))throw new AssertionError("world claim");checks++;
            rejected(()->accounts.claim(protocol,session),"world_auth_failed");
            rejected(()->accounts.gateway(protocol,"Tester","Driver",session,replacement),"gateway_token_unknown");
            String wrongSession=token(accounts);
            rejected(()->accounts.gateway(protocol,"Tester","Driver","wrong-session",wrongSession),"gateway_game_session_mismatch");
            rejected(()->accounts.gateway(protocol,"Tester","Other",session,wrongSession),"gateway_identity_mismatch");
            accounts.gateway(protocol,"Tester","Driver",session,wrongSession);checks++;
            // A failed connection followed by selecting another character reuses
            // the native protocol/session pair, but has a fresh per-character token.
            String passenger=post(accounts,"/security/issue_auth_token","login=Tester&username=Passenger").get("token").getAsString();
            var next=accounts.gateway(protocol,"Tester","Passenger",session,passenger);
            if(!next.name().equals("Passenger")||next.characterId().equals(grant.characterId()))throw new AssertionError("selected character ticket replacement");checks++;
            if(!next.equals(accounts.gateway(protocol,"Tester","Passenger",session,passenger)))throw new AssertionError("selected character retry");checks++;
            rejected(()->accounts.gateway(protocol,"Tester","Driver",session,wrongSession),"gateway_token_mismatch");
            rejected(()->accounts.gateway(protocol,"OtherAccount","Passenger",session,passenger),"gateway_identity_mismatch");
            if(!next.equals(accounts.claim(protocol,session)))throw new AssertionError("selected character world claim");checks++;
            rejected(()->accounts.claim(protocol,session),"world_auth_failed");
        }
        System.out.println("Gateway retry: "+checks+" PASS");
    }
}
