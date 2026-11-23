package szserver.client;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.net.*;
public final class RuntimeFilesTests {
    public static void main(String[] args)throws Exception {
        Path tmp=Files.createTempDirectory("packed-runtime-test-");
        try {
            for(String[] pair:new String[][]{{"classes","game.jar"},{"bootstrap","bootstrap.jar"},{"libs","libraries.jar"}}){
                try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(tmp.resolve(pair[1])))){z.putNextEntry(new ZipEntry("payload.dat"));z.write(new byte[]{1,4,9});z.closeEntry();}
                if(!Arrays.equals(RuntimeFiles.read(tmp.resolve(pair[0]+"/payload.dat")),new byte[]{1,4,9}))throw new AssertionError("packed bytes");
                try(URLClassLoader loader=new URLClassLoader(new URL[]{RuntimeFiles.location(tmp.resolve(pair[0])).toUri().toURL()},ClassLoader.getPlatformClassLoader())){
                    try(var in=loader.getResourceAsStream("payload.dat")){if(in==null||!Arrays.equals(in.readAllBytes(),new byte[]{1,4,9}))throw new AssertionError("classpath resources");}
                }
            }
            Path legacy=tmp.resolve("classes/payload.dat");Files.createDirectories(legacy.getParent());Files.write(legacy,new byte[]{2,5});
            if(!Arrays.equals(RuntimeFiles.read(legacy),new byte[]{2,5}))throw new AssertionError("legacy bytes");
            try{RuntimeFiles.read(tmp.resolve("classes/missing.class"));throw new AssertionError("missing entry");}catch(NoSuchFileException expected){}
            try{RuntimeFiles.read(tmp.resolve("missing.json"));throw new AssertionError("missing file");}catch(NoSuchFileException expected){}
            System.out.println("RuntimeFilesTests PASS: packed bytes, resource URLs and legacy fallback");
        }finally{RuntimeFiles.closeArchives();try(var paths=Files.walk(tmp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
