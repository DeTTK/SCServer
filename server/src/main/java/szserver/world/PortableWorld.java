package szserver.world;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import szserver.util.Json;

/** Offline creation and adoption. Only world-owned files are opened at runtime. */
public final class PortableWorld {
    private PortableWorld() {}
    private static final String MANIFEST="world.properties";
    private static final LinkOption NOFOLLOW=LinkOption.NOFOLLOW_LINKS;

    private static Path directory(Path path)throws IOException {
        if(!Files.isDirectory(path,NOFOLLOW)||!path.toRealPath().equals(path))throw new IOException("invalid_world_directory: "+path);
        return path;
    }
    private static void regular(Path path)throws IOException {
        if(!Files.isRegularFile(path,NOFOLLOW)||!path.toRealPath().equals(path))throw new IOException("invalid_world_file: "+path);
    }
    private static Properties layout(Path world)throws IOException {
        directory(world);
        Path manifest=world.resolve(MANIFEST);
        regular(manifest);
        if(Files.size(manifest)>16384)throw new IOException("world_manifest_too_large");
        Properties p=new Properties();
        try(var in=Files.newBufferedReader(manifest)){p.load(in);}
        String binding=p.getProperty("binding","");
        if(!"2".equals(p.getProperty("format"))||binding.isBlank()||binding.length()>4000||binding.indexOf(0)>=0
            ||!p.getProperty("mapSha256","").matches("[a-f0-9]{64}"))throw new IOException("invalid_world_manifest");
        Path map=directory(world.resolve("Map"));
        directory(map.resolve("region"));directory(world.resolve("data"));regular(SceneRegistry.sourceRegistry(map));regular(map.resolve("object-codecs.json"));
        try(var files=Files.newDirectoryStream(map.resolve("region"),"r.*.*.szr")){
            if(!files.iterator().hasNext())throw new IOException("world_has_no_regions");
        }
        return p;
    }
    /** Always derive these properties from the same checked world root. */
    public static void configure(Properties p,Path configDirectory)throws IOException {
        p.remove("world.bindingNamespace");
        if(!p.containsKey("world.directory"))return;
        Path world=configDirectory.resolve(p.getProperty("world.directory")).toRealPath();
        var manifest=layout(world);
        if(!p.getProperty("world.objectTemplateMap","").isBlank())throw new IOException("portable_world_external_template");
        p.setProperty("world.bindingNamespace",manifest.getProperty("binding"));
        p.setProperty("world.mode","regions");p.setProperty("world.resident.enabled","true");
        p.setProperty("world.regions",world.resolve("Map").toString());p.setProperty("data.directory",world.resolve("data").toString());
        p.setProperty("world.objectCodecs",world.resolve("Map/object-codecs.json").toString());p.setProperty("world.resident.saveSeconds","300");
    }
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static byte[] hash(Path file)throws IOException{
        var digest=sha();try(var in=new DigestInputStream(Files.newInputStream(file,NOFOLLOW),digest)){in.transferTo(OutputStream.nullOutputStream());}return digest.digest();
    }
    private static List<Path> children(Path dir)throws IOException {
        directory(dir);try(var paths=Files.list(dir)){return paths.sorted(Comparator.comparing(p->p.getFileName().toString())).toList();}
    }
    private static void digestTree(Path root,Path dir,MessageDigest digest)throws IOException {
        for(Path file:children(dir)){
            if(Files.isDirectory(file,NOFOLLOW)){digestTree(root,file,digest);continue;}
            regular(file);byte[] name=root.relativize(file).toString().replace(File.separatorChar,'/').getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(name.length).array());digest.update(name);digest.update(hash(file));
        }
    }
    private static String mapDigest(Path root)throws IOException{
        var digest=sha();digestTree(root,root,digest);return HexFormat.of().formatHex(digest.digest());
    }
    private static void copyFile(Path source,Path dest)throws IOException {
        regular(source);var before=Files.readAttributes(source,BasicFileAttributes.class,NOFOLLOW);
        Files.copy(source,dest,NOFOLLOW);
        try(var channel=FileChannel.open(dest,StandardOpenOption.WRITE)){channel.force(true);}
        var after=Files.readAttributes(source,BasicFileAttributes.class,NOFOLLOW);
        if(before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!MessageDigest.isEqual(hash(source),hash(dest)))throw new IOException("source_changed_or_copy_mismatch: "+source);
    }
    private static void copyTree(Path source,Path dest,boolean dataRoot)throws IOException {
        Files.createDirectory(dest);
        for(Path file:children(source)){
            if(dataRoot&&file.getFileName().toString().equals("server-owner.lock"))continue;
            Path target=dest.resolve(file.getFileName());
            if(Files.isDirectory(file,NOFOLLOW))copyTree(file,target,false);else copyFile(file,target);
        }
        syncDirectory(dest);
    }
    public static void syncDirectory(Path dir)throws IOException {
        // The Windows Java provider cannot open directory handles. Payloads and both heads are forced separately.
        if(File.separatorChar!='/')return;
        try(var channel=FileChannel.open(dir,StandardOpenOption.READ)){channel.force(true);}
    }
    /** Reproduce the source build's binding on the copied bytes before publishing an adopted world. */
    private static void verifyMigration(Path map,Path codecs,String binding,com.google.gson.JsonObject receipt)throws Exception {
        boolean managed=binding.startsWith("content-v2:");
        String expected=Json.text(receipt,"sourceBinding",4096),baseline=receipt.get("nativeBaseline").getAsString();
        String namespace=managed?"managed-map-content-v2":binding;
        Path target=codecs.toAbsolutePath().resolveSibling("live-gameobjects.json");
        boolean scenes=!receipt.get("registry").getAsString().isEmpty();
        SceneRegistry registry=null;
        if(scenes){
            if(!Files.isRegularFile(target))target=SceneRegistry.sourceRegistry(map);
            registry=new SceneRegistry(map,target,map.resolve("object-codecs.json"));
            if(!registry.target().digest().equals(receipt.get("registry").getAsString()))throw new IOException("legacy_target_registry_mismatch");
        }
        Path region=map.resolve("region");
        String content=CheckpointSources.regions(region,registry,region,registry,null,true,namespace);
        if(managed&&!binding.equals("content-v2:"+content))throw new IOException("legacy_managed_content_mismatch");
        String actual=baseline.isEmpty()?content:CheckpointSources.textDigest("szwc-native-live-v2:"+content+":"+baseline);
        if(!expected.equals(actual))throw new IOException("legacy_map_binding_mismatch_or_nondefault_region_settings");
    }
    private static void install(Path dest,Path map,Path codecs,Path legacy)throws Exception {
        Path parent=dest.toAbsolutePath().normalize().getParent().toRealPath();dest=parent.resolve(dest.getFileName());
        map=map.toRealPath();directory(map.resolve("region"));regular(SceneRegistry.sourceRegistry(map));
        if(legacy!=null)legacy=legacy.toRealPath();
        if(Files.exists(dest,NOFOLLOW)||dest.startsWith(map)||legacy!=null&&(dest.startsWith(legacy)||legacy.startsWith(map)||map.startsWith(legacy)))throw new IOException("world_destination_exists_or_overlaps");
        Path stage=Files.createTempDirectory(parent,".world-pending-");
        try(var lockFile=legacy==null?null:FileChannel.open(legacy.resolve("server-owner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,NOFOLLOW);
            var lease=lockFile==null?null:lockFile.tryLock()){
            if(lockFile!=null&&lease==null)throw new IOException("legacy_world_running");
            String binding=legacy==null?"world-v2/"+UUID.randomUUID():map.resolve("region").toRealPath().toString();
            if(legacy!=null&&Files.exists(legacy.resolve("resident-map.json"))){
                Path receipt=legacy.resolve("resident-map.json");regular(receipt);
                if(Files.size(receipt)>65536)throw new IOException("resident_map_marker_limit");
                String previous=Json.text(Json.parse(Files.readAllBytes(receipt)),"world",4096);
                // Launcher schema 1 shipped an agent that derived both identities from the map content.
                if(previous.matches("regions:content-v2:[0-9a-f]{64}"))binding=previous.substring("regions:".length());
                else if(!previous.equals("regions:"+binding))throw new IOException("legacy_world_identity_mismatch");
            }
            Path ownedMap=stage.resolve("Map");copyTree(map,ownedMap,false);
            if(!Files.exists(ownedMap.resolve("object-codecs.json")))copyFile(codecs.toRealPath(),ownedMap.resolve("object-codecs.json"));
            if(legacy!=null&&Files.isRegularFile(legacy.resolve("resident-map.json")))
                verifyMigration(ownedMap,codecs,binding,Json.parse(Files.readAllBytes(legacy.resolve("resident-map.json"))));
            Path data=stage.resolve("data");if(legacy==null)Files.createDirectory(data);else copyTree(legacy,data,true);
            var p=new Properties();p.setProperty("format","2");p.setProperty("binding",binding);p.setProperty("mapSha256",mapDigest(ownedMap));
            Path manifest=stage.resolve(MANIFEST);
            try(var writer=Files.newBufferedWriter(manifest,StandardOpenOption.CREATE_NEW)){p.store(writer,null);}
            try(var channel=FileChannel.open(manifest,StandardOpenOption.WRITE)){channel.force(true);}
            layout(stage);syncDirectory(ownedMap);syncDirectory(stage);
            Files.move(stage,dest,StandardCopyOption.ATOMIC_MOVE);syncDirectory(parent);
        }catch(Exception failure){throw new IOException("world_install_failed; staging: "+stage,failure);}
    }
    private static void verifyState(Path world,Properties p,boolean rollback)throws Exception {
        Path data=world.resolve("data"),marker=data.resolve("resident-map.json"),store=data.resolve("resident-map");
        try(var guard=FileChannel.open(data.resolve("server-owner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,NOFOLLOW);
            var lease=guard.tryLock()){
            if(lease==null)throw new IOException("world_running");
            if(Files.notExists(marker,NOFOLLOW)){
                if(rollback||Files.exists(store,NOFOLLOW))throw new IOException("resident_marker_missing");return;
            }
            regular(marker);directory(store);regular(store.resolve("world.binding"));
            if(Files.size(marker)>65536)throw new IOException("resident_map_marker_limit");
            var receipt=Json.parse(Files.readAllBytes(marker));
            if(!Json.text(receipt,"world",4096).equals("regions:"+p.getProperty("binding")))throw new IOException("resident_world_binding_mismatch");
            try(var disk=new RegionSnapshotStore(store,Json.text(receipt,"binding",4096),key->{throw new IOException("unexpected_base_read");})){
                if(rollback)disk.rollback();else disk.verifyPublished();
            }
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length==2&&(args[0].equals("verify")||args[0].equals("rollback"))){
            Path world=Path.of(args[1]).toRealPath();var p=layout(world);
            if(!p.getProperty("mapSha256").equals(mapDigest(world.resolve("Map"))))throw new IOException("world_map_digest_mismatch");
            verifyState(world,p,args[0].equals("rollback"));
        }else if(args.length==4&&args[0].equals("create")||args.length==5&&args[0].equals("migrate")){
            install(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),args.length==5?Path.of(args[4]):null);
        }else throw new IllegalArgumentException("create DEST MAP FALLBACK_CODECS | migrate DEST MAP FALLBACK_CODECS DATA | verify WORLD | rollback WORLD");
        System.out.println("world_format_2_ok");
    }
}
