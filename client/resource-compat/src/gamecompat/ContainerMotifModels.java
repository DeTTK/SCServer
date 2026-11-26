package gamecompat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** A material-only motif uses its target container's native geometry and framing. */
public final class ContainerMotifModels {
    private ContainerMotifModels() {}

    private static final ClassValue<Bindings> BINDINGS = new ClassValue<>() {
        @Override
        protected Bindings computeValue(Class<?> config) {
            try {
                return new Bindings(config);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Container model factory ABI changed", failure);
            }
        }
    };

    public static Object create(Object config, Object bake, Object preview, Object target) {
        var factory = BINDINGS.get(config.getClass());
        try {
            if (!((String) factory.modelName.get(config)).isEmpty()) {
                return factory.stock.invoke(null, config, bake, preview);
            }

            Object base = factory.baseRenderer.get(target);
            String material = (String) factory.materialName.get(config);
            if (base == null || material == null || material.isEmpty()) return base;

            Object modelView = factory.defaultView.get(factory.materialVariants.get(base));
            Object geometry = factory.modelLocation.get(factory.modelReference.get(modelView));
            Object paintedMaterial = factory.resourceLocation.invoke(null, factory.directory.get(null) + material);
            List<?> extras = (List<?>) factory.overrides.get(config);
            return factory.renderer.newInstance(geometry, paintedMaterial, factory.bake.get(base),
                    factory.preview.get(base), extras.isEmpty() ? factory.baseExtras.get(base) : extras);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException error) throw error;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Cannot create container motif model", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot resolve container motif geometry", failure);
        }
    }

    private static final class Bindings {
        final Field modelName, materialName, overrides, baseRenderer, materialVariants, defaultView,
                modelReference, modelLocation, directory, bake, preview, baseExtras;
        final Method stock, resourceLocation;
        final Constructor<?> renderer;

        Bindings(Class<?> config) throws ReflectiveOperationException {
            ClassLoader loader = config.getClassLoader();
            Class<?> target = Class.forName("1441854005", false, loader);
            Class<?> view = Class.forName("1982565452", false, loader);
            Class<?> bakeConfig = Class.forName("1144692372", false, loader);
            Class<?> previewConfig = Class.forName("1825675213", false, loader);
            Class<?> location = Class.forName("779455578", false, loader);
            modelName = config.getField("throws");
            materialName = config.getField("try");
            overrides = config.getField("protected");
            baseRenderer = target.getField("import");
            materialVariants = view.getField("return");
            defaultView = materialVariants.getType().getField("extends");
            modelReference = defaultView.getType().getField("synchronized");
            modelLocation = Class.forName("1520153458", false, loader).getField("default");
            directory = view.getField("continue");
            bake = view.getField("native");
            preview = view.getField("try");
            baseExtras = view.getField("double");
            stock = view.getMethod("try", config, bakeConfig, previewConfig);
            resourceLocation = Class.forName("1198595589", false, loader).getMethod("abstract", String.class);
            renderer = view.getConstructor(location, location, bakeConfig, previewConfig, List.class);
        }
    }
}
