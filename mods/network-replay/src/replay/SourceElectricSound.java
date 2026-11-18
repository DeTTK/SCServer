package replay;

/** Nullable sound field semantics from source 190767737.switch/reset/fade, independent of native field layout. */
final class SourceElectricSound {
    interface Factory {Object create()throws Exception;}
    final Factory factory;Object runner;long created,released;
    SourceElectricSound(Factory factory){this.factory=factory;}
    void enabled(boolean enabled,boolean profileSupplied)throws Exception {if(enabled&&profileSupplied&&runner==null){runner=factory.create();created++;}}
    void stopped(Object value){if(value!=null&&runner==value){runner=null;released++;}}
}
