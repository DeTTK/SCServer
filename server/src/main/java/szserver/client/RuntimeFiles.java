package szserver.client;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Reads the immutable player runtime from jars, retaining legacy directory support. */
public final class RuntimeFiles {
    private static final Map<Path,ZipFile> ARCHIVES=new HashMap<>();
    public static Path location(Path directory){
        if(directory.getFileName()==null)return directory;
        String name=directory.getFileName().toString();
        String jar=switch(name){case "classes"->"game.jar";case "libs"->"libraries.jar";case "bootstrap"->"bootstrap.jar";case "imgui-compat"->"imgui-compat.jar";default->null;};
        Path packed=jar==null?directory:directory.resolveSibling(jar);
        return Files.isRegularFile(packed)?packed:directory;
    }
    public static byte[] read(Path path)throws IOException {
        if(szserver.util.GameResources.mounted(path))return szserver.util.GameResources.read(path);
        if(Files.isRegularFile(path))return Files.readAllBytes(path);
        for(Path parent=path.getParent();parent!=null;parent=parent.getParent()){
            Path packed=location(parent);if(packed.equals(parent))continue;
            String name=parent.relativize(path).toString().replace('\\','/');
            if(name.startsWith("../")||name.startsWith("/"))throw new IOException("runtime_entry_path");
            ZipFile zip;
            synchronized(ARCHIVES){zip=ARCHIVES.get(packed);if(zip==null){if(ARCHIVES.size()>=32)throw new IOException("runtime_archive_limit");zip=new ZipFile(packed.toFile());ARCHIVES.put(packed,zip);}}
            ZipEntry entry=zip.getEntry(name);if(entry==null||entry.isDirectory())throw new NoSuchFileException(path.toString());
            if(entry.getSize()<0||entry.getSize()>64L*1024*1024)throw new IOException("runtime_entry_size");
            try(InputStream in=zip.getInputStream(entry)){byte[] bytes=in.readNBytes((int)entry.getSize()+1);if(bytes.length!=entry.getSize())throw new IOException("runtime_entry_size");return bytes;}
        }
        throw new NoSuchFileException(path.toString());
    }
    public static Path reference(Path manifest,String name){
        Path path=Path.of(name);return path.isAbsolute()?path:manifest.toAbsolutePath().getParent().resolve(path).normalize();
    }
    public static long size(Path path)throws IOException {if(szserver.util.GameResources.mounted(path))return szserver.util.GameResources.size(path);return Files.isRegularFile(path)?Files.size(path):read(path).length;}
    public static String readString(Path path)throws IOException{return new String(read(path),java.nio.charset.StandardCharsets.UTF_8);}
    static void closeArchives()throws IOException{synchronized(ARCHIVES){for(ZipFile zip:ARCHIVES.values())zip.close();ARCHIVES.clear();}}
    private RuntimeFiles(){}
}
