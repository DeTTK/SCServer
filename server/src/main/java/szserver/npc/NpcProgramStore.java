package szserver.npc;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Single bounded authoring worker owns this store. Acknowledges only a forced atomic commit. */
public final class NpcProgramStore {
    private final Path file;
    private long revision;
    private Map<String,NpcProgram> programs=Map.of();
    public NpcProgramStore(Path directory)throws IOException{file=directory.resolve("npc-programs.json");if(Files.exists(file)){if(Files.size(file)>512*1024)throw new IOException("npc_store_limit");JsonObject o=JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(o.get("version").getAsInt()!=1)throw new IOException("npc_store_version");revision=o.get("revision").getAsLong();if(revision<0)throw new IOException("npc_store_revision");var m=new TreeMap<String,NpcProgram>();for(var row:o.getAsJsonArray("programs")){var p=NpcProgram.parse(row.getAsJsonObject());if(m.put(p.name(),p)!=null||m.size()>128)throw new IOException("npc_store_duplicate_or_capacity");}programs=Map.copyOf(m);}}
    public long revision(){return revision;}
    public NpcProgram get(String name){var p=programs.get(name);if(p==null)throw new IllegalArgumentException("program_missing");return p;}
    public JsonObject catalog(){return json(revision,programs);}
    public void save(long expected,NpcProgram program)throws IOException{if(expected!=revision)throw new IllegalArgumentException("npc_revision_conflict_reload");var next=new TreeMap<>(programs);next.put(program.name(),program);if(next.size()>128)throw new IllegalArgumentException("npc_program_capacity");commit(next);}
    public void delete(long expected,String name)throws IOException{if(expected!=revision)throw new IllegalArgumentException("npc_revision_conflict_reload");if(!programs.containsKey(name))throw new IllegalArgumentException("program_missing");var next=new TreeMap<>(programs);next.remove(name);commit(next);}
    private static JsonObject json(long revision,Map<String,NpcProgram> programs){var o=new JsonObject();o.addProperty("version",1);o.addProperty("revision",revision);var list=new JsonArray();programs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->list.add(e.getValue().json()));o.add("programs",list);return o;}
    private void commit(Map<String,NpcProgram> next)throws IOException{byte[] bytes=json(revision+1,next).toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>512*1024)throw new IllegalArgumentException("npc_store_limit");Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".tmp");try{try(var c=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())c.write(buffer);c.force(true);}Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);programs=Map.copyOf(next);revision++;}finally{Files.deleteIfExists(temp);}}
}
