package uk.co.optimisticpanda.metricsdecorator;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.Pipe;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;

public class InterceptorHandlerFactory {
    private final Map<Class<? extends Annotation>, InterceptorFactory> interceptorProviders = new HashMap<>();

    public InterceptorHandlerFactory() {
    }

    public <T extends Annotation> InterceptorHandlerFactory register(Class<T> type, InterceptorFactory supplier) {
        interceptorProviders.put(type, supplier);
        return this;
    }

    public Type[] getRegsteredAnnotationTypes() {
        return interceptorProviders.keySet().toArray(new Type[interceptorProviders.keySet().size()]);
    }
    
    public InterceptorHandler create(Object delegate) {
        return new InterceptorHandler(interceptorProviders, delegate);
    }

    public static class InterceptorHandler {
        private final Map<Class<? extends Annotation>, InterceptorFactory> interceptorFactories;
        private final ConcurrentHashMap<Class<? extends Annotation>, Interceptor<? extends Annotation>> allInterceptors = new ConcurrentHashMap<>();
        private final Object delegate;

        private InterceptorHandler(Map<Class<? extends Annotation>, InterceptorFactory> interceptorProviders, Object delegate) {
            this.interceptorFactories = interceptorProviders;
            this.delegate = delegate;
        }

        @RuntimeType
        public Object intercept(@Pipe Piper pipe, @Origin Method method) throws Exception {
            Interceptors interceptors = gatherInterceptors(method);
            try {
                Optional<Object> result = interceptors.onBefore(delegate, method);
                if (result.isPresent()) {
                    return result.get();
                }
                Object actualResult = pipe.to(delegate);
                return interceptors.onAfter(delegate, method, actualResult).orElse(actualResult);
            } catch (Exception e) {
                Optional<Object> result = interceptors.onException(delegate, method, e);
                if (result.isPresent()) {
                    return result.get();
                }
                throw e;
            } finally {
                interceptors.onFinally(delegate, method);
            }
        }

        private Interceptors gatherInterceptors(Method method) {
            return stream(method.getAnnotations())
                    .<AnnotationInfo>flatMap(a-> stream(a.getClass().getInterfaces()).map(iClass -> new AnnotationInfo(a, iClass)))
                    .filter(a -> interceptorFactories.containsKey(a.getRealType()))
                    .map(a -> createInterceptor(a, delegate))
                    .collect(collectingAndThen(toList(), Interceptors::new));
        }

        private Interceptor<? extends Annotation> createInterceptor(AnnotationInfo info, Object delegate) {
            Function<Class<? extends Annotation>, Interceptor<? extends Annotation>> f = annotation -> {
                InterceptorFactory factory = interceptorFactories.get(annotation);
                Annotation ann = info.getAnnotation();
                return factory.build(ann, delegate);
            };
            Interceptor<? extends Annotation> interceptor = allInterceptors.computeIfAbsent(info.getRealType(), f);
            return interceptor;
        }
        
    }
    
    public interface InterceptorFactory {
        Interceptor<?> build(Annotation annotation, Object delegate);
    }
    
    private static class AnnotationInfo {
        private final Annotation annotation;
        private final Class<? extends Annotation> realType;
        
        @SuppressWarnings("unchecked")
        private AnnotationInfo(Annotation annotation, Class<?> realType) {
            super();
            this.annotation = annotation;
            this.realType = (Class<? extends Annotation>) realType;
        }
        
        public Class<? extends Annotation> getRealType() {
            return realType;
        }
        
        public Annotation getAnnotation() {
            return annotation;
        }
    }
}
