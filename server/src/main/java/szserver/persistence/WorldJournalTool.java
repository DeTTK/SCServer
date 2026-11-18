package szserver.persistence;

import java.nio.file.Path;

/** Offline maintenance of a stopped realm. Portable export keeps the exact original logical bytes. */
public final class WorldJournalTool {
    public static void main(String[] args)throws Exception{
        if(args.length<2||args[0].equals("pack")&&args.length!=2||args[0].equals("export-plain")&&args.length!=3||!java.util.Set.of("pack","export-plain").contains(args[0]))throw new IllegalArgumentException("usage: pack <journal> | export-plain <journal> <new-output>");
        try(Journal journal=Journal.streaming(Path.of(args[1]).toRealPath())){journal.pinOffsets();if(args[0].equals("pack"))journal.packHistory().get();else journal.exportPlain(Path.of(args[2])).get();if(!journal.healthy())throw new IllegalStateException("world_journal_unhealthy");System.out.println("PASS world journal "+args[0]+":records="+journal.durablePrefix().get().records()+",logicalBytes="+journal.metrics().get("logicalBytes")+",physicalBytes="+journal.metrics().get("physicalBytes"));}
    }
}
