package org.nfcresearch.qpolllab;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Resolves the system Binder API once, before changing NFC state. */
final class ReaderModeCompat {
    private final Method method;

    private ReaderModeCompat(Method method) {
        this.method = method;
    }

    static ReaderModeCompat resolve(Class<?> adapter, Class<?> binder, Class<?> callback,
                                    Class<?> bundle) throws NoSuchMethodException {
        try {
            return new ReaderModeCompat(adapter.getMethod("setReaderMode",
                    binder, callback, int.class, bundle, String.class));
        } catch (NoSuchMethodException fiveMissing) {
            try {
                return new ReaderModeCompat(adapter.getMethod("setReaderMode",
                        binder, callback, int.class, bundle));
            } catch (NoSuchMethodException fourMissing) {
                NoSuchMethodException missing = new NoSuchMethodException(
                        adapter.getName() + ".setReaderMode：系统未提供受支持的四参数或五参数接口");
                missing.initCause(fourMissing);
                missing.addSuppressed(fiveMissing);
                throw missing;
            }
        }
    }

    int parameterCount() {
        return method.getParameterTypes().length;
    }

    void set(Object service, Object token, Object callback, int flags, Object extras,
             String packageName) throws Exception {
        Object[] args = parameterCount() == 5
                ? new Object[]{token, callback, flags, extras, packageName}
                : new Object[]{token, callback, flags, extras};
        try {
            method.invoke(service, args);
        } catch (InvocationTargetException e) {
            // A service failure must never trigger a second call through another overload.
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw e;
        }
    }
}
