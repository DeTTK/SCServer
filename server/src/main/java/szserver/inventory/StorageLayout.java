package szserver.inventory;
/** Installed native storage layout; generated from actual game config, never UI button order. */
public final class StorageLayout {
    public static final int PAGES=15, BAG=160, EQUIPMENT=12;
    private static final int[] CAPACITIES={800,800,800,800,800,800,800,800,800,800,800,800,8000,800,800};
    public static final int MAX_CAPACITY=8000, MAX_ROOTS=19372;
    private StorageLayout(){}
    public static int capacity(int page){if(page<0||page>=PAGES)throw new IllegalArgumentException("invalid_storage_page");return CAPACITIES[page];}
    public static boolean purchases(int page){capacity(page);return page==12;}
    public static boolean canDeposit(int page){return !purchases(page);}
    public static boolean validAddress(Inventory.Address a){return a!=null&&(a.aggregate()==0&&a.container()>=0&&a.container()<EQUIPMENT&&a.slot()==0||a.aggregate()==1&&a.container()==0&&a.slot()>=0&&a.slot()<BAG||a.aggregate()==2&&a.container()>=0&&a.container()<PAGES&&a.slot()>=0&&a.slot()<capacity(a.container()));}
}
