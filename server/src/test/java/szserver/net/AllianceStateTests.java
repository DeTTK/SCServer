package szserver.net;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;

public final class AllianceStateTests {
    static int checks;static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    static String await(CompletableFuture<String> value)throws Exception{return value.get(5,TimeUnit.SECONDS);}
    static void reject(CompletableFuture<?> value)throws Exception{try{value.get(5,TimeUnit.SECONDS);throw new AssertionError("invalid operation accepted");}catch(ExecutionException expected){check(expected.getCause() instanceof IllegalArgumentException||expected.getCause() instanceof IOException,"validation/storage error surfaced");}}
    static void test(Path root)throws Exception{
        Path directory=root.resolve("main");
        try(AllianceState state=new AllianceState(directory)){
            check(Files.isRegularFile(state.file()),"settings created");
            check(await(state.set("duty","farm",0)).contains("скрыта"),"zero hides perk");check(!state.active("duty").contains("farm"),"hidden row inactive");
            check(await(state.add("duty","farm")).contains("добавлен"),"add restores hidden perk");await(state.set("duty","quest",25));await(state.remove("duty","buyer"));await(state.set("duty","fee",250000));await(state.set("duty","стоимость",1000));
            for(String id:List.of("stalker","stalkers","bandit","bandits"))check(await(state.set(id,"fee",200)).contains("200"),"legacy/native names accepted");
            for(double bad:new double[]{-1,-.1,100000001,4294967296d,Double.MAX_VALUE,Double.NaN,Double.POSITIVE_INFINITY,1.5})reject(state.set("duty","fee",bad));
            check(await(state.set("freedom","farm",2.3)).contains("2.3%"),"chat percentages preserve decimal formatting");
            for(double bad:new double[]{-1001,1001,Double.NaN,Double.NEGATIVE_INFINITY})reject(state.set("duty","farm",bad));
            reject(state.set("nobody","fee",5));reject(state.set("duty","unknown",5));
            check(state.describe().contains("duty — 1000 ₽"),"failed commands preserve fee");
            byte[] good=Files.readAllBytes(state.file()),frame=state.frame();long revision=state.revision();
            for(String invalid:List.of("{","[]","{\"duty\":null}","{\"duty\":{\"fee\":4294967296}}","{\"duty\":{\"fee\":-500}}","{\"duty\":{\"fee\":1.5}}","{\"duty\":{\"farm\":\"NaN\"}}","{\"duty\":{\"farm\":1e400}}","{\"duty\":{\"bonuses\":null}}")){
                Files.writeString(state.file(),invalid);reject(state.reload());check(Arrays.equals(frame,state.frame())&&state.revision()==revision,"invalid reload does not publish partial state");
            }
            Files.write(state.file(),new byte[AllianceState.MAX_FILE_BYTES+1]);reject(state.reload());Files.write(state.file(),good);await(state.reload());
            byte[] returned=state.frame();returned[0]^=1;check(!Arrays.equals(returned,state.frame()),"frame snapshot cannot be mutated by caller");
            byte[] payload=Wire.readFrame(new ByteArrayInputStream(state.frame()));ByteBuffer buffer=ByteBuffer.wrap(payload);check(Wire.varint(buffer)==1667,"native packet ID");DataInputStream in=Wire.input(Arrays.copyOfRange(payload,buffer.position(),payload.length));
            check(in.readInt()==6,"six native factions");for(String id:AllianceState.ALLIANCES){check(in.readUTF().equals(id)&&in.readUTF().isEmpty(),"native name and label");check(!in.readBoolean()&&!in.readBoolean(),"native recruitment suspension flags are disabled");for(int i=0;i<6;i++)check(Float.isFinite(in.readFloat()),"finite native percentages");int price=in.readInt();check(price>=0&&price<=100000000,"bounded native fee");check(in.readBoolean()&&in.readInt()==0,"nullable expiry and overrides");}check(!in.readBoolean()&&in.available()==0,"native refresh flag and framing");
            check(await(AllianceCommands.execute(state,"/alliance bandits fee 500")).contains("500"),"command native faction alias");check(await(AllianceCommands.execute(state,"/фракции duty add добыча 12,5")).contains("12.5"),"Russian command and decimal comma");check(await(AllianceCommands.execute(state,"/alliance duty list")).contains("farm"),"list parser");
        }
        try(AllianceState restarted=new AllianceState(directory)){check(restarted.describe().contains("duty — 1000 ₽"),"fee survives restart");check(!restarted.active("duty").contains("buyer"),"hidden perk survives restart");}
        AtomicBoolean fail=new AtomicBoolean();Path transaction=root.resolve("transaction");
        try(AllianceState state=new AllianceState(transaction,(file,bytes)->{if(fail.get())throw new IOException("test_write_failed");AllianceState.store(file,bytes);})){byte[] before=state.frame(),disk=Files.readAllBytes(state.file());long revision=state.revision();fail.set(true);reject(state.set("duty","fee",42));check(Arrays.equals(before,state.frame())&&revision==state.revision(),"failed write does not change live state");check(Arrays.equals(disk,Files.readAllBytes(state.file())),"failed save preserves old file");fail.set(false);await(state.set("duty","fee",43));check(state.describe().contains("duty — 43 ₽"),"storage recovers after failed command");}
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean block=new AtomicBoolean();AtomicReference<String> thread=new AtomicReference<>();
        try(AllianceState state=new AllianceState(root.resolve("queue"),(file,bytes)->{thread.set(Thread.currentThread().getName());if(block.get()){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test_timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}AllianceState.store(file,bytes);})){block.set(true);String old=Thread.currentThread().getName();CompletableFuture<String> first;try{Thread.currentThread().setName("native-game-owner");first=state.set("duty","fee",1);}finally{Thread.currentThread().setName(old);}check(entered.await(2,TimeUnit.SECONDS)&&!first.isDone(),"owner admission never waits for storage");check(thread.get().startsWith("alliance-storage-"),"disk IO is on dedicated worker");check(state.describe().contains("duty — 100000 ₽"),"readers retain old snapshot during write");List<CompletableFuture<String>> pending=new ArrayList<>();for(int i=0;i<32;i++)pending.add(state.set("duty","fee",i+2));reject(state.set("duty","fee",999));release.countDown();await(first);for(var item:pending)await(item);check(state.describe().contains("duty — 33 ₽"),"bounded FIFO preserves accepted command order");}finally{release.countDown();}
        AllianceState closed=new AllianceState(root.resolve("closed"));closed.close();reject(closed.set("duty","fee",1));
    }
    static void playerRelations(){
        var factions=AllianceState.ALLIANCES;
        // Include a directed alliance so server rules use the configured matrix, not just faction equality.
        var relations=new AreaRelations(factions.stream().map(name->
                new AreaRelations.Alliance(name,name,name.equals("stalkers")?Set.of("duty"):Set.of())).toList());
        var mode=AreaRelations.Mode.DEFAULT;
        var squads=new PlayerSquads();
        var a=new PlayerSquads.Player(UUID.randomUUID(),"TesterA");
        var b=new PlayerSquads.Player(UUID.randomUUID(),"TesterB");
        var idA=UUID.randomUUID();
        var idB=UUID.randomUUID();
        long party=squads.invite(a,b,1);
        check(squads.id(b.session())==null,"a pending invite gives no protection");
        squads.accept(b,party,2);
        check(squads.id(a.session()).equals(squads.id(b.session())),"accepted squad is shared");
        check(ByteBuffer.wrap(Factions.partyAttribute(squads.id(b.session()))).getLong()==party,"native party attribute is a long");
        for(String left:factions)for(String right:factions){
            var attacker=new AreaRelations.Actor(idA,left,left,null,null,false);
            var victim=new AreaRelations.Actor(idB,right,right,null,null,false);
            boolean allied=left.equals(right)||left.equals("stalkers")&&right.equals("duty");
            check(relations.allied(attacker,victim,mode)==allied,"alliance without a squad follows the native matrix");
            check(relations.allowsPlayerDamage(false,attacker,victim)!=allied,"default damage follows faction relations");
            check(relations.allowsPlayerDamage(true,attacker,victim),"friendly fire allows damage without changing relations");
            var squadOwner=new AreaRelations.Actor(idA,left,left,null,squads.id(a.session()),false);
            var squadVictim=new AreaRelations.Actor(idB,right,right,null,squads.id(b.session()),false);
            check(relations.selected("ALLIES",squadOwner,squadVictim,mode),"shared squad overrides different factions");
            check(!relations.allowsPlayerDamage(false,squadOwner,squadVictim),"default protects squad members");
            check(relations.allowsPlayerDamage(true,squadOwner,squadVictim),"friendly fire allows damage to squad members");
        }
        squads.leave(b);
        check(ByteBuffer.wrap(Factions.partyAttribute(squads.id(b.session()))).getLong()==-1L,"leave clears the native party id");
        check(!relations.allied(relations.actor(idA,"duty",-1L),relations.actor(idB,"freedom",-1L),mode),
                "native no-party sentinel is not a squad");
        var player=relations.actor(idA,"duty");
        check(relations.allowsPlayerDamage(false,player,player),"own explosives retain their damage");
        check(relations.allowsPlayerDamage(false,null,player),"world damage is unaffected");
    }
    static void friendlyFireCommands(Path root)throws Exception{
        try(var state=new AllianceState(root)){
            check(!state.friendlyFire(),"old worlds start with friendly fire disabled");
            await(AllianceCommands.friendlyFire(state,"/friendlyfire on"));
            check(state.friendlyFire(),"setting applies while the server is running");
            check(await(AllianceCommands.friendlyFire(state,"/friendlyfire status")).contains("включён"),"status reads live value");
        }
        var refuseWrite=new AtomicBoolean(true);
        try(var state=new AllianceState(root,(path,data)->{
            if(refuseWrite.get()&&path.getFileName().toString().equals("friendly-fire.txt"))throw new IOException("write_failed");
            AllianceState.store(path,data);
        })){
            check(state.friendlyFire(),"value survives restart");
            reject(AllianceCommands.friendlyFire(state,"/friendlyfire off"));
            check(state.friendlyFire(),"failed save leaves the live setting intact");
            refuseWrite.set(false);
            await(AllianceCommands.friendlyFire(state,"/friendlyfire off"));
            check(!state.friendlyFire(),"disable takes effect without restart");
        }
        try(var state=new AllianceState(root)){check(!state.friendlyFire(),"off survives restart");}
    }
    public static void main(String[] args)throws Exception{Path base=Path.of(args[0]);Files.createDirectories(base);Path root=Files.createTempDirectory(base,"alliances-");try{test(root);playerRelations();friendlyFireCommands(root.resolve("friendly-fire"));}finally{try(var walk=Files.walk(root)){for(Path path:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}System.out.println("PASS "+checks+" alliance settings, native framing, party relations, friendly fire and persistence checks");}
}
