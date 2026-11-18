package szserver.client;

/** javac generates two value() descriptors here, reproducing the lookup ambiguity. */
public final class NativeMethodsTests {
    public static class Parent { public Number value() { return 1; } }
    public static final class Child extends Parent {
        @Override public Integer value() { return 2; }
        public static int stationary() { return 3; }
        public int argument(int value) { return value; }
    }

    public static void main(String[] args) throws Exception {
        Child child = new Child();
        var integer = NativeMethods.instance(Child.class, "value", Integer.class);
        var number = NativeMethods.instance(Child.class, "value", Number.class);
        if (integer.equals(number) || !integer.invoke(child).equals(2)
                || number.getReturnType() != Number.class) throw new AssertionError("descriptor");
        if (!NativeMethods.instance(Child.class, "argument", int.class, int.class)
                .invoke(child, 7).equals(7)) throw new AssertionError("parameters");
        missing("value", Boolean.class);
        missing("stationary", int.class);
        missing("argument", int.class);
        System.out.println("Native method descriptors: 5 PASS");
    }

    private static void missing(String name, Class<?> result) throws Exception {
        try {
            NativeMethods.instance(Child.class, name, result);
            throw new AssertionError("Unexpected descriptor: " + name);
        } catch (NoSuchMethodException expected) { }
    }
}
