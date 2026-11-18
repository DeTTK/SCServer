package replay;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Only small manifests are read while listing files; payloads load on selection. */
final class ReplayLibrary {
    record Entry(Path path,long durationNs,int events,String error){}
    final Path directory;
    volatile List<Entry> entries=List.of();volatile String error="";
    final AtomicBoolean refreshing=new AtomicBoolean();
    ReplayLibrary(Path directory){this.directory=directory.toAbsolutePath().normalize();}
    void refresh(){
        if(!refreshing.compareAndSet(false,true))return;
        Thread thread=new Thread(()->{
            try{
                Files.createDirectories(directory);List<Entry> found=new ArrayList<>();Set<Path> listed=new HashSet<>();
                try(var files=Files.list(directory)){for(Path path:files.filter(p->Files.isRegularFile(p)&&p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".demo")).sorted(Comparator.comparing(p->p.getFileName().toString(),String.CASE_INSENSITIVE_ORDER)).toList()){
                    try(ZipFile zip=new ZipFile(path.toFile())){
                        ZipEntry manifest=zip.getEntry("manifest.json");if(manifest==null)throw new IOException("Нет manifest.json");
                        byte[] json;try(InputStream in=zip.getInputStream(manifest)){json=in.readNBytes(65537);if(json.length>65536)throw new IOException("Слишком большой manifest.json");}
                        JsonObject m=JsonParser.parseString(new String(json,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                        if(m.has("sessions")&&m.getAsJsonArray("sessions").size()>1){
                            for(Path session:ReplayArchives.split(new DemoFile(path),directory))if(listed.add(session)){DemoFile part=new DemoFile(session);found.add(new Entry(session,part.durationNs,part.events.size(),""));}continue;
                        }
                        if(!m.get("format").getAsString().equals("stalcraft-network-demo")||m.get("version").getAsInt()!=1)throw new IOException("Неподдерживаемый формат");
                        long duration=m.get("duration_ns").getAsLong();int count=m.get("event_count").getAsInt();if(duration<0||count<1)throw new IOException("Некорректная длительность или число событий");
                        if(listed.add(path))found.add(new Entry(path,duration,count,""));
                    }catch(Exception e){found.add(new Entry(path,0,0,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
                }}
                entries=List.copyOf(found);error="";
            }catch(Exception e){error=e.toString();}finally{refreshing.set(false);}
        },"Replay library");thread.setDaemon(true);thread.start();
    }
}
