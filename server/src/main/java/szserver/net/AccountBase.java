package szserver.net;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Parse one row at a time. Dependency-ordered passes avoid a whole-store JSON DOM. */
final class AccountBase {
    private static final Set<String> TABLES=Set.of("accounts","profiles","tokens","tickets","pendingCharacterDeletes");
    record Header(int version,UUID journalId,long revision,boolean legacyArray){}
    private static JsonReader reader(Path file)throws IOException{JsonReader value=new JsonReader(Files.newBufferedReader(file,StandardCharsets.UTF_8));value.setStrictness(Strictness.STRICT);return value;}
    static Header header(Path file)throws IOException{
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IllegalArgumentException("account_base_not_regular");
        try(JsonReader in=reader(file)){if(in.peek()==JsonToken.BEGIN_ARRAY)return new Header(0,null,0,true);int version=-1;UUID id=null;long revision=0;Set<String> seen=new HashSet<>();in.beginObject();while(in.hasNext()){String name=in.nextName();if(!seen.add(name))throw new IllegalArgumentException("account_base_duplicate_field");switch(name){case "version"->version=in.nextInt();case "accountJournalId"->id=UUID.fromString(in.nextString());case "accountJournalRevision"->revision=new java.math.BigDecimal(in.nextString()).longValueExact();default->{if(!TABLES.contains(name))throw new IllegalArgumentException("account_base_field");in.skipValue();}}}in.endObject();if(in.peek()!=JsonToken.END_DOCUMENT||version<2||version>6||revision<0||id==null&&revision!=0||!seen.contains("profiles")||version>=3&&!seen.contains("tickets")||version>=6&&!seen.contains("accounts"))throw new IllegalArgumentException("account_store_version_or_revision");return new Header(version,id,revision,false);}
    }
    static void visit(Path file,String table,java.util.function.Consumer<JsonElement> handler)throws IOException{
        try(JsonReader in=reader(file)){if(in.peek()==JsonToken.BEGIN_ARRAY){if(!table.equals("legacy"))throw new IllegalArgumentException("account_base_legacy_table");in.beginArray();while(in.hasNext())handler.accept(JsonParser.parseReader(in));in.endArray();}
            else{in.beginObject();while(in.hasNext()){String name=in.nextName();if(!name.equals(table)){in.skipValue();continue;}in.beginArray();while(in.hasNext())handler.accept(JsonParser.parseReader(in));in.endArray();}in.endObject();}if(in.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException("account_base_trailing_data");}
    }
}
