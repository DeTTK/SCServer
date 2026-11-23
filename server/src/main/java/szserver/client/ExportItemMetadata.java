package szserver.client;

import java.nio.file.*;
import com.google.gson.Gson;

/** Offline developer utility. Output is private game metadata, not a source-tree asset. */
public final class ExportItemMetadata {
    public static void main(String[] args)throws Exception{
        if(args.length!=5)throw new IllegalArgumentException("runtime registry definitions artifact-settings output");
        try(var items=new GameItems(Path.of(args[0]),Path.of(args[1]))){items.warmGameplay(Path.of(args[2]),Path.of(args[3]));var result=items.exportFactoryMetadata();Files.writeString(Path.of(args[4]),new Gson().toJson(result));System.out.println("Native export rows="+result.rows().size()+" unavailable constructors="+result.errors().size());}
    }
    private ExportItemMetadata(){}
}
