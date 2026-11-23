package bscp.next;

import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real HTTP and existing v1 signatures; no game assets, external DNS or player data. */
public final class RouteOriginTest {
    public static final class Backend {
        final AtomicInteger calls=new AtomicInteger();
        public void bscpOriginalHandle(HttpExchange e)throws Exception {
            calls.incrementAndGet();
            String host=szserver.net.ConnectionRoutes.requestHost(e.getRequestHeaders().getFirst("Host"));
            String text=host+"\n"+e.getRequestURI()+"\n"+new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        }
    }
    record Request(String method,String path,String body,String time,String nonce,String proof){}
    static KeyPair keys;static HttpClient client=HttpClient.newHttpClient();static int port,checks;
    static Request signed(String method,String path,String body)throws Exception {
        String time=Long.toString(System.currentTimeMillis()/1000),nonce=UUID.randomUUID().toString().replace("-","")+"12345678";
        String message=method+"\n"+path+"\n"+time+"\n"+nonce+"\n"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Signature signature=Signature.getInstance("SHA256withECDSAinP1363Format");signature.initSign(keys.getPrivate());signature.update(message.getBytes(StandardCharsets.UTF_8));
        return new Request(method,path,body,time,nonce,Base64.getEncoder().encodeToString(signature.sign()));
    }
    static HttpResponse<String> send(Request r,String actualBody)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+r.path)).header("Content-Type","application/x-www-form-urlencoded")
            .header("X-BSCP-Key",Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())).header("X-BSCP-Time",r.time).header("X-BSCP-Nonce",r.nonce).header("X-BSCP-Signature",r.proof)
            .header("X-Forwarded-Host","attacker.invalid");
        return client.send(builder.method(r.method,HttpRequest.BodyPublishers.ofString(actualBody)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static void check(boolean value,String what){if(!value)throw new AssertionError(what);checks++;}
    public static void main(String[] args)throws Exception {
        var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));keys=generator.generateKeyPair();
        var backend=new Backend();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);server.createContext("/",e->AccountGuard.handle(backend,e));server.start();port=server.getAddress().getPort();
        try {
            String body="login=Player&__bscp_route=play.example.test%3A443";var request=signed("POST","/session/game",body);var result=send(request,body);
            check(result.statusCode()==200,"POST accepted");check(result.body().startsWith("play.example.test\n"),"signed route overrides reverse proxy Host");check(!result.body().contains("__bscp_route"),"reserved POST field removed");check(result.body().contains("login=bscp-"),"account namespace retained");
            check(send(request,body).statusCode()==401,"replay rejected");
            request=signed("GET","/address_list?x=%2f&__bscp_route=%5B2001%3Adb8%3A%3A7%5D%3A29545","");result=send(request,"");
            check(result.statusCode()==200,"GET accepted");check(result.body().startsWith("2001:db8::7\n"),"IPv6 origin selected");check(!result.body().contains("__bscp_route"),"reserved GET field removed");
            request=signed("POST","/session/game",body);check(send(request,body.replace("play.example.test","other.example.test")).statusCode()==401,"route tampering rejected");
            request=signed("POST","/session/game","login=Legacy");result=send(request,request.body);check(result.statusCode()==200&&result.body().startsWith("127.0.0.1\n"),"legacy signed clients retained");
            request=signed("POST","/session/game","__bscp_route=https%3A%2F%2Fevil.invalid%2Fpath");check(send(request,request.body).statusCode()==401,"invalid origin rejected");
            check(backend.calls.get()==3,"failed requests never reached backend");
            System.out.println("PASS "+checks+" signed connection-origin HTTP checks");
        } finally {server.stop(0);}
    }
}
