package szserver.world;

import com.google.gson.JsonObject;
import szserver.persistence.Journal;
import szserver.util.Json;
import java.io.*;
import java.util.*;

/** Segmented current-state records sharing the resident map's atomic generation. */
public final class ResidentPlayerPages {
    private static final int PAGE_BYTES=1024*1024, MAX_RECORD=65536;
    private final String name;

    public ResidentPlayerPages(String name){
        if(!name.matches("[a-z]{1,24}"))throw new IllegalArgumentException("resident_player_page_name");
        this.name="players-"+name;
    }

    String root(){return name+".json";}
    private String page(int index){return name+"-"+Integer.toHexString(index)+".bin";}
    public boolean owns(String file){return file.startsWith(name+"-")||file.equals(root());}

    /** Must read from a pinned generation for the entire load. */
    public Journal.SnapshotReader reader(ResidentChunks.StateFiles files)throws Exception{
        ResidentChunks.StateIndex index=files.index();
        if(!index.files().containsKey(root()))return null;
        var manifest=load(files,index,root());
        if(manifest.length>1024)throw new IOException("resident_player_root_size");
        var fields=Json.parse(manifest);
        if(!fields.keySet().equals(Set.of("format","pages","rows"))
                ||fields.get("format").getAsInt()!=1)throw new IOException("resident_player_root_format");
        int pages=fields.get("pages").getAsBigDecimal().intValueExact();
        long expected=fields.get("rows").getAsBigDecimal().longValueExact();
        if(pages<0||pages>65535||expected<0)throw new IOException("resident_player_root_bounds");
        var contents=new ArrayList<byte[]>(pages);
        long total=0;
        for(int i=0;i<pages;i++){
            if(!Objects.equals(index.files().get(root()),index.files().get(page(i))))
                throw new IOException("resident_player_page_revision");
            byte[] data=load(files,index,page(i));
            total+=data.length;
            if(data.length<5||data.length>PAGE_BYTES||total>128L*1024*1024)
                throw new IOException("resident_player_page_size");
            contents.add(data);
        }
        Journal.SnapshotReader reader=sink->{
            long count=0;
            for(byte[] data:contents){
                try(var in=new DataInputStream(new ByteArrayInputStream(data))){
                    while(in.available()>0){
                        int length=in.readInt();
                        if(length<1||length>MAX_RECORD||length>in.available())throw new IOException("resident_player_row_size");
                        sink.accept(Json.parse(in.readNBytes(length)));
                        count++;
                    }
                }
            }
            if(count!=expected)throw new IOException("resident_player_row_count");
        };
        try{reader.read(row->{});}catch(RuntimeException invalid){throw new IOException("resident_player_page_json",invalid);}
        return reader;
    }

    private static byte[] load(ResidentChunks.StateFiles files,ResidentChunks.StateIndex index,String name)throws Exception{
        var data=files.load(name);
        if(data==null||!Objects.equals(index.files().get(name),data.revision()))
            throw new IOException("resident_player_page_missing:"+name);
        return data.bytes();
    }

    public ResidentChunks.StateDelta encode(long revision,Journal.StreamingCheckpoint current,Set<String> previous)throws Exception{
        var output=new PageWriter();
        current.write(output::append);
        output.flush();
        var manifest=new JsonObject();
        manifest.addProperty("format",1);manifest.addProperty("pages",output.files.size());manifest.addProperty("rows",output.rows);
        output.files.add(new ResidentChunks.StateFile(root(),Json.bytes(manifest)));
        Set<String> removed=new HashSet<>();
        for(String file:previous)if(owns(file))removed.add(file);
        for(var file:output.files)removed.remove(file.name());
        return new ResidentChunks.StateDelta(revision,output.files,removed);
    }

    private final class PageWriter {
        private final List<ResidentChunks.StateFile> files=new ArrayList<>();
        private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        private final DataOutputStream data=new DataOutputStream(buffer);
        private long rows,bytes;

        void append(JsonObject row)throws Exception{
            byte[] encoded=Json.bytes(row);
            if(encoded.length>MAX_RECORD)throw new IOException("resident_player_row_size");
            if(buffer.size()+4+encoded.length>PAGE_BYTES)flush();
            data.writeInt(encoded.length);data.write(encoded);rows++;
        }
        void flush()throws IOException{
            if(buffer.size()==0)return;
            bytes+=buffer.size();
            if(bytes>128L*1024*1024)throw new IOException("resident_player_save_capacity");
            files.add(new ResidentChunks.StateFile(page(files.size()),buffer.toByteArray()));
            buffer.reset();
        }
    }
}
