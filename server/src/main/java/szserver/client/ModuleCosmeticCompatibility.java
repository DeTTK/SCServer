package szserver.client;

/** Typed bridge for genuine module and cosmetic factories sharing a gun's attachment map. */
public final class ModuleCosmeticCompatibility {
    public static Object childFactory(ClientStateAdapter adapter,int id)throws Exception{return adapter.childFactory(id);}
    private ModuleCosmeticCompatibility(){}
}
