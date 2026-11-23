package bscp.next;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;

/** Signed native backend requests with replay protection and key-based account namespaces. */
public final class AccountGuard {
    static final ConcurrentHashMap<String,Long> nonces=new ConcurrentHashMap<>();
    public static void handle(Object backend,HttpExchange exchange){try{
        String path=exchange.getRequestURI().getPath();
        if(exchange.getRequestMethod().equals("GET")&&(path.equals("/health")||path.equals("/check_version"))){original(backend,exchange);return;}
        if(path.startsWith("/launcher/")||!Set.of("GET","POST").contains(exchange.getRequestMethod())){Bridge.respond(exchange,403,"{\"error\":\"managed_launcher_required\"}");return;}
        byte[] body=exchange.getRequestBody().readNBytes(8193);if(body.length>8192)throw new IllegalArgumentException("body_limit");
        var headers=exchange.getRequestHeaders();String publicKey=headers.getFirst("X-BSCP-Key"),time=headers.getFirst("X-BSCP-Time"),nonce=headers.getFirst("X-BSCP-Nonce"),proof=headers.getFirst("X-BSCP-Signature");
        if(publicKey==null||publicKey.length()>1024||time==null||nonce==null||!nonce.matches("[0-9a-f]{40}")||proof==null||proof.length()>256)throw new IllegalArgumentException("launcher_identity_required");
        long now=System.currentTimeMillis()/1000,stamp=Long.parseLong(time);if(Math.abs(now-stamp)>120)throw new IllegalArgumentException("launcher_clock_skew");
        String requestPath=exchange.getRequestURI().toASCIIString();String message=exchange.getRequestMethod()+"\n"+requestPath+"\n"+time+"\n"+nonce+"\n"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        byte[] keyBytes=Base64.getDecoder().decode(publicKey);var key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyBytes));var signature=Signature.getInstance("SHA256withECDSAinP1363Format");signature.initVerify(key);signature.update(message.getBytes(StandardCharsets.UTF_8));if(!signature.verify(Base64.getDecoder().decode(proof)))throw new IllegalArgumentException("launcher_identity_invalid");
        nonces.entrySet().removeIf(e->e.getValue()<now-120);if(nonces.size()>8192)throw new IllegalStateException("launcher_identity_busy");String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keyBytes));if(nonces.putIfAbsent(fingerprint+nonce,stamp)!=null)throw new IllegalArgumentException("launcher_request_replayed");
        // The game keeps its own character names. Account login is a stable key namespace.
        String raw=exchange.getRequestMethod().equals("POST")?new String(body,StandardCharsets.UTF_8):Objects.toString(exchange.getRequestURI().getRawQuery(),"");
        var fields=new LinkedHashMap<String,String>();for(String field:raw.split("&")){String[] kv=field.split("=",2);if(kv.length==2)fields.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
        // This value was covered by the existing signature (query or body). It is
        // chosen by the launcher's connection, not by an unsigned Forwarded header.
        String route=fields.remove("__bscp_route");
        if(route!=null){if(route.length()>1024||Class.forName("szserver.net.ConnectionRoutes",false,backend.getClass().getClassLoader()).getMethod("requestHost",String.class).invoke(null,route)==null)throw new IllegalArgumentException("launcher_route_invalid");}
        fields.put("login","bscp-"+fingerprint);String replaced=fields.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        HttpExchange wrapped=new QueryExchange(exchange,exchange.getRequestURI(),route);
        if(exchange.getRequestMethod().equals("POST"))exchange.setStreams(new ByteArrayInputStream(replaced.getBytes(StandardCharsets.UTF_8)),null);
        else wrapped=new QueryExchange(exchange,URI.create(exchange.getRequestURI().getRawPath()+"?"+replaced),route);
        original(backend,wrapped);
    }catch(Throwable error){try{Bridge.respond(exchange,401,"{\"error\":"+Bridge.quote(Objects.toString(error.getMessage(),"identity_failed"))+"}");}catch(Exception ignored){exchange.close();}}}
    static void original(Object backend,HttpExchange exchange)throws Exception{var method=backend.getClass().getDeclaredMethod("bscpOriginalHandle",HttpExchange.class);method.setAccessible(true);method.invoke(backend,exchange);}
    static final class QueryExchange extends HttpExchange {
        final HttpExchange original;final URI uri;final Headers headers;
        QueryExchange(HttpExchange e,URI u,String route){original=e;uri=u;headers=new Headers();headers.putAll(e.getRequestHeaders());if(route!=null)headers.set("Host",route);}
        public Headers getRequestHeaders(){return headers;}public Headers getResponseHeaders(){return original.getResponseHeaders();}public URI getRequestURI(){return uri;}public String getRequestMethod(){return original.getRequestMethod();}public HttpContext getHttpContext(){return original.getHttpContext();}public void close(){original.close();}public InputStream getRequestBody(){return original.getRequestBody();}public OutputStream getResponseBody(){return original.getResponseBody();}public void sendResponseHeaders(int code,long length)throws IOException{original.sendResponseHeaders(code,length);}public InetSocketAddress getRemoteAddress(){return original.getRemoteAddress();}public int getResponseCode(){return original.getResponseCode();}public InetSocketAddress getLocalAddress(){return original.getLocalAddress();}public String getProtocol(){return original.getProtocol();}public Object getAttribute(String name){return original.getAttribute(name);}public void setAttribute(String name,Object value){original.setAttribute(name,value);}public void setStreams(InputStream in,OutputStream out){original.setStreams(in,out);}public HttpPrincipal getPrincipal(){return original.getPrincipal();}
    }
}
