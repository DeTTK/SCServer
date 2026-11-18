package szserver.migration;

import com.google.gson.JsonObject;
import szserver.persistence.Journal;
import java.nio.file.*;

/** Private, forced offline event plan. Only a complete verified prefix may be replayed. */
final class LegacyEventPlan implements AutoCloseable {
    private final Path directory;private final Journal.Prefix prefix;
    private LegacyEventPlan(Path directory,Journal.Prefix prefix){this.directory=directory;this.prefix=prefix;}
    static LegacyEventPlan empty(){return new LegacyEventPlan(null,null);}
    long records(){return prefix==null?0:prefix.records();}
    void visit(Journal.Recovery sink)throws Exception{if(prefix==null)return;Path file=directory.resolve("plan.sjournal");if(Files.size(file)!=prefix.end())throw new IllegalArgumentException("legacy_event_plan_changed");try(Journal journal=Journal.streaming(file)){if(!journal.matches(prefix))throw new IllegalArgumentException("legacy_event_plan_changed");journal.replay(sink).get();}}
    private static void remove(Path directory)throws Exception{if(directory==null)return;try(var files=Files.list(directory)){for(Path path:files.toList()){if(Files.isSymbolicLink(path)||!Files.isRegularFile(path))throw new IllegalArgumentException("legacy_event_plan_private_file");Files.delete(path);}}Files.delete(directory);}
    @Override public void close()throws Exception{remove(directory);}
    static final class Builder implements AutoCloseable {
        final Path directory;private Journal journal;private boolean sealed;
        Builder(Path scratch,String label)throws Exception{Files.createDirectories(scratch);directory=Files.createTempDirectory(scratch,label);try{journal=Journal.streaming(directory.resolve("plan.sjournal"));}catch(Exception error){remove(directory);throw error;}}
        void append(JsonObject event)throws Exception{if(sealed||journal==null)throw new IllegalStateException("legacy_event_plan_finished");journal.append(event).get();}
        LegacyEventPlan seal()throws Exception{Journal.Prefix prefix=journal.prefix().get();journal.close();journal=null;sealed=true;return new LegacyEventPlan(directory,prefix);}
        @Override public void close()throws Exception{if(journal!=null){journal.close();journal=null;}if(!sealed)remove(directory);}
    }
}
