package szserver.commands;

import com.google.gson.*;
import szserver.persistence.*;
import szserver.util.Json;
import szserver.world.ResidentChunks;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Paged transport for the existing complete command-state reducers. Runs only on save/bootstrap workers. */
public final class ResidentCommandCheckpoint {
    public static final String ROOT="command-state";
    private static final String PAGE="command-page-";
    private static final int ROOT_MAGIC=0x535a4352,PAGE_MAGIC=0x535a4350,VERSION=1,TARGET=256*1024,MAX_PAGES=400000;
    private static final List<String> SECTIONS=WorldCheckpointStore.SECTIONS.subList(6,12);
    private ResidentCommandCheckpoint(){}
    private static void worker(){String name=Thread.currentThread().getName();if(name.equals("world-tick")||name.equals("native-game-owner"))throw new IllegalStateException("command_checkpoint_on_owner");}
    public record Binding(String world,String registry,long revision,Journal.Prefix source){public Binding{if(world==null||world.isBlank()||world.length()>4096||registry==null||registry.length()>4096||revision<0)throw new IllegalArgumentException("resident_command_binding");}public Binding(String world,String registry,long revision){this(world,registry,revision,null);}}
    @FunctionalInterface public interface Pages {void write(ResidentChunks.StateFile file)throws Exception;}
    private record Ref(int bucket,int ordinal,String name,int bytes,int rows){}
    private record Section(long rows,List<Ref> pages){Section{pages=List.copyOf(pages);}}
    public record Encoded(Binding binding,ResidentChunks.StateFile root,Set<String> pages,long bytes,long rows){public Encoded{pages=Set.copyOf(pages);}public Set<String> names(){Set<String> names=new HashSet<>(pages);names.add(ROOT);return Set.copyOf(names);}}
    public static final class Snapshot {
        private final CommandStateCheckpoint.Export export;
        private Snapshot(CommandStateCheckpoint.Export export){this.export=export;}
        /** Emit changed page bodies only. Existing names must come from the selected durable cohort. */
        public Encoded encode(Binding binding,Set<String> existing,Pages pages)throws Exception{
            worker();Objects.requireNonNull(binding);Objects.requireNonNull(existing);Objects.requireNonNull(pages);var sink=new Writer(existing,pages);CommandStateCheckpoint.write(sink,export);if(sink.section!=SECTIONS.size()-1||sink.open)throw new IOException("resident_command_sections");
            byte[] manifest=manifest(binding,sink.sections);return new Encoded(binding,new ResidentChunks.StateFile(ROOT,manifest),sink.names,sink.bytes,sink.rows);
        }
    }
    static Snapshot capture(CommandStateCheckpoint.State state){worker();return new Snapshot(CommandStateCheckpoint.capture(state));}
    private static byte[] manifest(Binding binding,List<Section> sections)throws IOException{
        var buffer=new ByteArrayOutputStream();try(var out=new DataOutputStream(buffer)){out.writeInt(ROOT_MAGIC);out.writeInt(VERSION);out.writeUTF(binding.world());out.writeUTF(binding.registry());out.writeLong(binding.revision());out.writeBoolean(binding.source()!=null);if(binding.source()!=null){out.writeLong(binding.source().end());out.writeLong(binding.source().records());out.writeUTF(binding.source().sha256());}out.writeInt(sections.size());for(var section:sections){out.writeLong(section.rows());out.writeInt(section.pages().size());for(var ref:section.pages()){out.writeShort(ref.bucket());out.writeInt(ref.ordinal());out.writeUTF(ref.name());out.writeInt(ref.bytes());out.writeInt(ref.rows());}}}if(buffer.size()>64*1024*1024)throw new IOException("resident_command_manifest_size");return buffer.toByteArray();
    }
    private static final class Buffer {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();final DataOutputStream out=new DataOutputStream(bytes);int rows;
        void add(byte[] row)throws IOException{out.writeInt(row.length);out.write(row);rows++;}
    }
    private static final class Writer implements CheckpointSink.Logical {
        final Set<String> existing,names=new HashSet<>();final Pages target;final List<Section> sections=new ArrayList<>();
        final Map<Integer,Buffer> buffers=new HashMap<>();final Map<Integer,Integer> ordinals=new HashMap<>();final List<Ref> refs=new ArrayList<>();
        int section=-1;boolean open;long sectionRows,rows,bytes;
        Writer(Set<String> existing,Pages target){this.existing=Set.copyOf(existing);this.target=target;}
        public void begin(String name)throws IOException{if(open||++section>=SECTIONS.size()||!SECTIONS.get(section).equals(name))throw new IOException("resident_command_section_order");open=true;sectionRows=0;buffers.clear();ordinals.clear();refs.clear();}
        public void row(JsonObject row)throws IOException{throw new IOException("resident_command_logical_rows_required");}
        public void logicalRow(JsonObject row,byte[] encoded)throws IOException{
            if(!open)throw new IOException("resident_command_section_required");int bucket=bucket(row);Buffer buffer=buffers.computeIfAbsent(bucket,k->new Buffer());if(buffer.rows>0&&buffer.bytes.size()+4L+encoded.length>TARGET){flush(bucket);buffer=buffers.computeIfAbsent(bucket,k->new Buffer());}buffer.add(encoded);sectionRows++;rows++;if(buffer.bytes.size()>=TARGET)flush(bucket);
        }
        private int bucket(JsonObject row)throws IOException{
            String kind=Json.text(row,"kind",32);if(kind.endsWith("_control"))return 0;
            JsonElement key=row.get("id");if(section==1&&kind.equals("item"))key=row.get("owner");if(section==2&&!kind.equals("change"))key=row.getAsJsonObject("value").get("object_id");
            if(key==null)return 257;try{return 1+(UUID.fromString(key.getAsString()).hashCode()&255);}catch(RuntimeException invalid){throw new IOException("resident_command_row_key",invalid);}
        }
        private void flush(int bucket)throws IOException{
            Buffer buffer=buffers.remove(bucket);if(buffer==null||buffer.rows==0)return;int ordinal=ordinals.getOrDefault(bucket,0);ordinals.put(bucket,ordinal+1);
            var data=new ByteArrayOutputStream();try(var out=new DataOutputStream(data)){out.writeInt(PAGE_MAGIC);out.writeInt(VERSION);out.writeInt(section);out.writeInt(bucket);out.writeInt(buffer.rows);buffer.bytes.writeTo(out);}byte[] payload=data.toByteArray();String name=PAGE+CheckpointDataRows.hash(payload);
            if(names.add(name)){if(names.size()>MAX_PAGES)throw new IOException("resident_command_page_limit");bytes=Math.addExact(bytes,payload.length);if(!existing.contains(name))try{target.write(new ResidentChunks.StateFile(name,payload));}catch(IOException failure){throw failure;}catch(Exception failure){throw new IOException("resident_command_page_write",failure);}}
            refs.add(new Ref(bucket,ordinal,name,payload.length,buffer.rows));
        }
        public void end()throws IOException{if(!open)throw new IOException("resident_command_section_required");for(int bucket:new TreeSet<>(buffers.keySet()))flush(bucket);refs.sort(Comparator.comparingInt(Ref::bucket).thenComparingInt(Ref::ordinal));sections.add(new Section(sectionRows,refs));open=false;}
    }
    private record Manifest(Binding binding,List<Section> sections){}
    private static Manifest readManifest(byte[] bytes)throws IOException{
        if(bytes.length<28||bytes.length>64*1024*1024)throw new IOException("resident_command_manifest_size");try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=ROOT_MAGIC||in.readInt()!=VERSION)throw new IOException("resident_command_version");String world=in.readUTF(),registry=in.readUTF();long revision=in.readLong();int hasSource=in.readUnsignedByte();if(hasSource>1)throw new IOException("resident_command_source_flag");Journal.Prefix source=hasSource==0?null:new Journal.Prefix(in.readLong(),in.readLong(),in.readUTF());Binding binding=new Binding(world,registry,revision,source);if(in.readInt()!=SECTIONS.size())throw new IOException("resident_command_sections");List<Section> sections=new ArrayList<>();int total=0;Set<String> names=new HashSet<>();
            for(int s=0;s<SECTIONS.size();s++){long rows=in.readLong();int count=in.readInt();if(rows<1||count<1||count>MAX_PAGES-total)throw new IOException("resident_command_page_count");total+=count;List<Ref> refs=new ArrayList<>();int previous=-1,ordinal=0;long actual=0;for(int n=0;n<count;n++){int bucket=in.readUnsignedShort(),order=in.readInt();String name=in.readUTF();int length=in.readInt(),pageRows=in.readInt();if(bucket>257||bucket<previous||order!=(bucket==previous?ordinal+1:0)||!name.matches("command-page-[0-9a-f]{64}")||!names.add(name)||length<24||length>CheckpointDataRows.MAX_BYTES+24||pageRows<1||pageRows>(length-20)/5)throw new IOException("resident_command_page_reference");actual=Math.addExact(actual,pageRows);refs.add(new Ref(bucket,order,name,length,pageRows));previous=bucket;ordinal=order;}if(refs.get(0).bucket()!=0||actual!=rows)throw new IOException("resident_command_row_count");sections.add(new Section(rows,refs));}if(in.available()!=0)throw new IOException("resident_command_manifest_trailing");return new Manifest(binding,List.copyOf(sections));
        }catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("resident_command_manifest_invalid",invalid);}
    }
    static CommandStateCheckpoint.State restore(ResidentChunks.StateFiles files,Path base,String world,String registry,long maximumRevision)throws Exception{
        worker();var saved=files.load(ROOT);if(saved==null)throw new IOException("resident_command_root_missing");var manifest=readManifest(saved.bytes());if(!manifest.binding().world().equals(world)||!manifest.binding().registry().equals(registry)||manifest.binding().revision()>maximumRevision||saved.revision()>maximumRevision)throw new IOException("resident_command_binding");
        var reader=new CommandStateCheckpoint.Reader(base);try{
            for(int s=0;s<SECTIONS.size();s++)for(var ref:manifest.sections().get(s).pages()){
                var page=files.load(ref.name());if(page==null||page.revision()>maximumRevision)throw new IOException("resident_command_page_missing");byte[] payload=page.bytes();if(payload.length!=ref.bytes()||!ref.name().equals(PAGE+CheckpointDataRows.hash(payload)))throw new IOException("resident_command_page_hash");
                try(var in=new DataInputStream(new ByteArrayInputStream(payload))){if(in.readInt()!=PAGE_MAGIC||in.readInt()!=VERSION||in.readInt()!=s||in.readInt()!=ref.bucket()||in.readInt()!=ref.rows())throw new IOException("resident_command_page_header");for(int n=0;n<ref.rows();n++){int length=in.readInt();if(length<1||length>CheckpointDataRows.MAX_BYTES||length>in.available())throw new IOException("resident_command_row_length");reader.accept(SECTIONS.get(s),Json.parse(in.readNBytes(length),2*1024*1024));}if(in.available()!=0)throw new IOException("resident_command_page_trailing");}
            }return reader.finish();
        }catch(RuntimeException failure){reader.discard();throw new IOException("resident_command_state_invalid",failure);}catch(Exception|Error failure){reader.discard();throw failure;}
    }
    public static Binding binding(ResidentChunks.StateFiles files)throws Exception{worker();var root=files.load(ROOT);if(root==null)throw new IOException("resident_command_root_missing");return readManifest(root.bytes()).binding();}
}
