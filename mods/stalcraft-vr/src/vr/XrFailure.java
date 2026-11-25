package vr;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Translate only a fresh SteamVR error belonging to this client. */
final class XrFailure {
    static boolean missingHeadset(String message){return message.startsWith("SteamVR: шлем не подключён.")||message.startsWith("SteamVR: шлем не найден.");}
    static String message(Throwable failure,long attemptedAt){
        String runtime=System.getenv("XR_RUNTIME_JSON");
        if(runtime!=null)try{
            for(Path parent=Path.of(runtime).toAbsolutePath().getParent();parent!=null;parent=parent.getParent()){
                Path log=parent.resolve("logs/xrclient_java.txt");
                if(!Files.isRegularFile(log)||Files.getLastModifiedTime(log).toMillis()<attemptedAt)continue;
                try(FileChannel file=FileChannel.open(log,StandardOpenOption.READ)){
                    long size=file.size();ByteBuffer tail=ByteBuffer.allocate((int)Math.min(size,65536));file.position(size-tail.capacity());while(tail.hasRemaining()&&file.read(tail)>0){}tail.flip();
                    String reason=steamReason(StandardCharsets.UTF_8.decode(tail).toString(),ProcessHandle.current().pid());
                    if(reason!=null)return reason;
                }
            }
        }catch(Exception ignored){}
        return "VR: "+failure.getMessage()+". Подробности в журнале клиента.";
    }
    static String steamReason(String log,long pid){
        int startup=log.lastIndexOf("startup with PID=");if(startup<0)return null;
        String latest=log.substring(startup);if(!latest.startsWith("startup with PID="+pid+","))return null;
        if(latest.contains("Received connect response VRInitError_Driver_WirelessHmdNotConnected"))
            return "SteamVR: шлем не подключён. Подключите Steam Link, затем F12.";
        if(latest.contains("Received connect response VRInitError_Init_HmdNotFound"))
            return "SteamVR: шлем не найден. Подключите шлем, затем F12.";
        return null;
    }
}
