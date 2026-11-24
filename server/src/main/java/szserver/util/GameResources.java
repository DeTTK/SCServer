package szserver.util;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Read-only logical resource roots backed by original XEON archives and explicit Git overrides. */
public final class GameResources {
    private record Mount(Path root,XeonArchive archive,Path overrides){}
    private static final Map<Path,Mount> ROOTS=new LinkedHashMap<>();
    public static synchronized void mount(Path root,Path archive,Path overrides)throws IOException {
        root=root.toAbsolutePath().normalize();if(ROOTS.size()>=8&&!ROOTS.containsKey(root))throw new IOException("resource_root_limit");
        ROOTS.put(root,new Mount(root,new XeonArchive(archive),overrides==null?null:overrides.toAbsolutePath().normalize()));
    }
    private static synchronized Mount find(Path path){Path full=path.toAbsolutePath().normalize();return ROOTS.values().stream().filter(m->full.startsWith(m.root)).max(Comparator.comparingInt(m->m.root.getNameCount())).orElse(null);}
    static Object mountKey(Path path){return find(path);}
    public static boolean mounted(Path path){return find(path)!=null;}
    private static String key(Mount m,Path path)throws IOException{return XeonArchive.safe(m.root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\','/'));}
    private static Path override(Mount m,String name)throws IOException {
        if(m.overrides==null)return null;Path p=m.overrides.resolve(name).normalize();if(!p.startsWith(m.overrides))throw new IOException("resource_override_path");
        if(!Files.isRegularFile(p))return null;if(!p.toRealPath().startsWith(m.overrides.toRealPath()))throw new IOException("resource_override_link");return p;
    }
    public static byte[] read(Path path)throws IOException{Mount m=find(path);if(m==null)return Files.readAllBytes(path);String key=key(m,path);Path p=override(m,key);return p==null?m.archive.read(key):Files.readAllBytes(p);}
    public static String readString(Path path)throws IOException{return new String(read(path),StandardCharsets.UTF_8);}
    public static long size(Path path)throws IOException{Mount m=find(path);if(m==null)return Files.size(path);String key=key(m,path);Path p=override(m,key);return p==null?m.archive.size(key):Files.size(p);}
    public static InputStream stream(Path path)throws IOException{return mounted(path)?new ByteArrayInputStream(read(path)):Files.newInputStream(path);}
    public static boolean isFile(Path path){try{Mount m=find(path);return m==null?Files.isRegularFile(path):m.archive.contains(key(m,path))||override(m,key(m,path))!=null;}catch(IOException e){return false;}}
    public static boolean exists(Path path){Mount m=find(path);if(m==null)return Files.exists(path);Path full=path.toAbsolutePath().normalize();if(full.equals(m.root))return true;if(isFile(path))return true;String prefix=m.root.relativize(full).toString().replace('\\','/')+"/";return m.archive.names().stream().anyMatch(n->n.startsWith(prefix));}
    public static Path real(Path path)throws IOException{if(!mounted(path))return path.toRealPath();if(!exists(path))throw new NoSuchFileException(path.toString());return path.toAbsolutePath().normalize();}
    public static Stream<Path> walk(Path path)throws IOException {
        Mount m=find(path);if(m==null)return Files.walk(path);Set<Path> files=new LinkedHashSet<>();Path full=path.toAbsolutePath().normalize();
        for(String name:m.archive.names()){Path p=m.root.resolve(name);if(p.startsWith(full))files.add(p);}
        if(m.overrides!=null&&Files.isDirectory(m.overrides))try(var paths=Files.walk(m.overrides)){for(Path p:paths.filter(Files::isRegularFile).toList()){Path target=m.root.resolve(m.overrides.relativize(p));if(target.startsWith(full))files.add(target);}}
        return files.stream();
    }
    public static URL url(Path root)throws IOException {
        if(!mounted(root))return root.toRealPath().toUri().toURL();Path logical=real(root);
        String prefix="/"+Base64.getUrlEncoder().withoutPadding().encodeToString(logical.toString().getBytes(StandardCharsets.UTF_8))+"/";
        return new URL(null,"xeon://resources"+prefix,new URLStreamHandler(){protected URLConnection openConnection(URL url){return new URLConnection(url){
            public void connect(){}
            private Path entry()throws IOException {String name;try{name=url.toURI().getPath();}catch(URISyntaxException e){throw new IOException("resource_url",e);}if(!name.startsWith(prefix))throw new IOException("resource_url_root");return logical.resolve(XeonArchive.safe(name.substring(prefix.length())));}
            public InputStream getInputStream()throws IOException{return stream(entry());}
            public long getContentLengthLong(){try{return size(entry());}catch(IOException e){return -1;}}
        };}});
    }
    static synchronized void clear(){ROOTS.clear();}
    private GameResources(){}
}
