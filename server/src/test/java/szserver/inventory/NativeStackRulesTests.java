package szserver.inventory;

import com.google.gson.*;import java.nio.file.*;import java.util.*;
public final class NativeStackRulesTests {
    static int checks;static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
    static void rejects(Runnable r){try{r.run();throw new AssertionError("invalid rule accepted");}catch(IllegalArgumentException e){checks++;}}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);Path file=root.resolve("item-definitions.json");
        Files.writeString(file,"{\"1\":{\"type\":\"medicine\"},\"2\":{\"type\":\"medicine\",\"stack_size\":5},\"3\":{\"type\":\"game_object\",\"object_id\":\"parent\",\"stacksize\":10},\"4\":{\"type\":\"game_object\",\"extends_gameobject\":\"parent\",\"stack_size\":999},\"5\":{\"type\":\"game_object\"},\"6\":{\"type\":\"weapon\"},\"16999\":{\"type\":\"quest_item\",\"stack_size\":5}}");
        // A previously shipped partial defaults file must not hide newer reviewed families.
        Files.writeString(root.resolve("native-resource-stack-defaults.json"),"{\"version\":1,\"defaultLimits\":{\"custom\":100}}");
        ItemCatalog c=new ItemCatalog(file);check(c.get(1).stackLimit()==64,"native medicine constructor default");check(c.get(2).stackLimit()==5,"authored medicine override");check(c.get(3).stackLimit()==10&&c.get(4).stackLimit()==10,"game-object stacksize and inherited stacksize override generic stack_size");check(c.get(5).stackLimit()==64,"game-object native fallback");check(c.get(6).stackLimit()==1,"weapons remain singletons");check(c.get(16999).stackLimit()==1&&ItemCatalog.legacyGeometry(file).get(16999).stackLimit()==5,"stateful builtin retains recoverable old generic limit");
        for(var e:NativeStackRules.DEFAULTS.entrySet()){JsonObject row=new JsonObject();row.addProperty("type",e.getKey());check(NativeStackRules.limit(900,row,Map.of(),false)==e.getValue(),"constructor default "+e.getKey());}
        Inventory empty=new Inventory(c);var grant=empty.give(new ItemStack(1,130,0,new byte[0]));check(grant.after().items().values().stream().map(ItemStack::count).toList().equals(List.of(64,64,2)),"large grant packs native medicine stacks");
        Inventory bag=grant.after();var moved=bag.quick(new Inventory.Address(1,0,0),List.of(new Inventory.Address(2,0,0)),0).after();moved=moved.quick(new Inventory.Address(1,0,1),List.of(new Inventory.Address(2,0,0)),0).after();check(moved.at(new Inventory.Address(2,0,0)).count()==128,"storage merges beyond bag limit");
        var returned=moved.quick(new Inventory.Address(2,0,0),List.of(new Inventory.Address(1,0,0),new Inventory.Address(1,0,1)),0).after();check(returned.at(new Inventory.Address(1,0,0)).count()==64&&returned.at(new Inventory.Address(1,0,1)).count()==64,"storage withdrawal splits at native bag cap");
        var split=returned.move(new Inventory.Address(1,0,0),new Inventory.Address(1,0,3),10,true).after();check(split.at(new Inventory.Address(1,0,0)).count()==54&&split.at(new Inventory.Address(1,0,3)).count()==10,"manual partial split preserves quantities");
        var a=new ItemStack(1,1,0,new byte[0],new byte[]{0});var b=new ItemStack(1,1,0,new byte[0],new byte[]{1});check(!a.mergeable(b),"different native binding/expiry properties never merge");
        JsonObject parent=JsonParser.parseString("{\"type\":\"game_object\",\"extends_gameobject\":\"cycle\"}").getAsJsonObject();rejects(()->NativeStackRules.limit(5,parent,Map.of("cycle",parent),false));JsonObject invalid=new JsonObject();invalid.addProperty("stack_size",0);rejects(()->NativeStackRules.limit(1,invalid,Map.of(),false));
        System.out.println("PASS "+checks+" native stack rules, grants, storage, splits and inherited configuration checks");
    }
}
