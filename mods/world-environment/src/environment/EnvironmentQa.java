package environment;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Opt-in loopback diagnostics for isolated, mouse-free functional QA only. */
public final class EnvironmentQa {
    private static final ArrayBlockingQueue<JsonObject> actions=new ArrayBlockingQueue<>(8);
    private static volatile String snapshot="{}";
    private static boolean enabled;
    private static long next;
    public static void start()throws Exception {
        int port=Integer.getInteger("sz.environment.qa.port",0);if(port==0)return;
        if(!Boolean.getBoolean("sz.client.isolatedTest"))throw new IllegalArgumentException("Environment QA requires isolatedTest");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),4);
        server.createContext("/state",exchange->{byte[] data=snapshot.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);try(var out=exchange.getResponseBody()){out.write(data);}});
        server.createContext("/action",exchange->{int code=405;if(exchange.getRequestMethod().equals("POST")){byte[] raw=exchange.getRequestBody().readNBytes(8193);if(raw.length<=8192&&actions.offer(JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject()))code=202;else code=429;}exchange.sendResponseHeaders(code,-1);exchange.close();});
        server.setExecutor(Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Environment QA loopback");t.setDaemon(true);return t;}));server.start();enabled=true;
    }
    public static void frame(){if(!enabled)return;try{JsonObject action=actions.poll();if(action!=null)EnvironmentBridge.qaAction(action);if(System.nanoTime()>=next){next=System.nanoTime()+500_000_000;snapshot=EnvironmentBridge.qaState().toString();}}catch(Exception error){JsonObject out=new JsonObject();out.addProperty("qaError",error.toString());snapshot=out.toString();}}
}
