package vr;
import java.nio.file.*;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Read-only preflight: do not repeatedly bootstrap SteamVR on the game thread without a HMD. */
final class SteamVrReady {
    static boolean steamRuntime(String manifest){
        if(manifest==null||manifest.isBlank())return false;
        try{return Files.readString(Path.of(manifest)).matches("(?s).*\\\"VALVE_runtime_is_steamvr\\\"\\s*:\\s*true.*");}
        catch(Exception unavailable){return false;}
    }
    static boolean compositor(Stream<String> commands){return commands.anyMatch(command->{String path=command.replace('\\','/').toLowerCase(Locale.ROOT);return path.endsWith("/vrcompositor.exe")||path.equals("vrcompositor.exe");});}
    static boolean ready(boolean steam,Supplier<Stream<String>> processes){if(!steam)return true;try(Stream<String> commands=processes.get()){return compositor(commands);}catch(Exception unavailable){return false;}}
    static boolean ready(boolean steam){return ready(steam,()->ProcessHandle.allProcesses().map(process->process.info().command().orElse("")));}
}
