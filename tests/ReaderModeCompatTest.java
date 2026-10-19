package org.nfcresearch.qpolllab;

import java.io.IOException;

public final class ReaderModeCompatTest {
    public static final class Token { }
    public static final class Callback { }
    public static final class Extras { }
    public interface FourApi {
        void setReaderMode(Token token, Callback callback, int flags, Extras extras) throws Exception;
    }
    public interface FiveApi {
        void setReaderMode(Token token, Callback callback, int flags, Extras extras, String pkg) throws Exception;
    }
    public interface BothApi extends FourApi, FiveApi { }

    public static final class Service implements BothApi {
        int fourCalls, fiveCalls, flags;
        Token token;
        Callback callback;
        Extras extras;
        String pkg;
        Exception failure;

        public void setReaderMode(Token token, Callback callback, int flags, Extras extras) throws Exception {
            fourCalls++;
            record(token, callback, flags, extras, null);
        }
        public void setReaderMode(Token token, Callback callback, int flags, Extras extras, String pkg) throws Exception {
            fiveCalls++;
            record(token, callback, flags, extras, pkg);
        }
        private void record(Token token, Callback callback, int flags, Extras extras, String pkg) throws Exception {
            this.token = token; this.callback = callback; this.flags = flags;
            this.extras = extras; this.pkg = pkg;
            if (failure != null) throw failure;
        }
    }
    public interface WrongApi {
        void setReaderMode(Token token, Callback callback, int flags, String extras);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static ReaderModeCompat resolve(Class<?> api) throws Exception {
        return ReaderModeCompat.resolve(api, Token.class, Callback.class, Extras.class);
    }
    public static void main(String[] args) throws Exception {
        Token token = new Token(); Callback callback = new Callback(); Extras extras = new Extras();
        for (Class<?> api : new Class<?>[]{FourApi.class, FiveApi.class, BothApi.class}) {
            Service service = new Service();
            ReaderModeCompat compat = resolve(api);
            int count = api == FourApi.class ? 4 : 5;
            check(compat.parameterCount() == count, "wrong signature selection");
            check(service.fourCalls + service.fiveCalls == 0, "resolution must not call service");
            for (int flags : new int[]{0x81, 0, 0x81, 0}) {
                compat.set(service, token, callback, flags, extras, "com.android.shell");
                check(service.token == token && service.callback == callback && service.extras == extras
                        && service.flags == flags, "arguments must survive enable, reconnect and cleanup");
                check(count == 4 ? service.pkg == null : "com.android.shell".equals(service.pkg),
                        "package name must only be sent to five-argument API");
            }
            check(service.fourCalls == (count == 4 ? 4 : 0)
                    && service.fiveCalls == (count == 5 ? 4 : 0), "must reuse selected overload");
        }
        for (Class<?> api : new Class<?>[]{Object.class, WrongApi.class}) {
            try {
                resolve(api);
                throw new AssertionError("unsupported signature accepted");
            } catch (NoSuchMethodException expected) {
                check(expected.getCause() instanceof NoSuchMethodException
                        && expected.getSuppressed().length == 1, "preserve both lookup failures");
            }
        }
        // Even a NoSuchMethodException thrown inside the service is an invocation failure.
        for (Exception failure : new Exception[]{new SecurityException("denied"),
                new IOException("binder failure"), new NoSuchMethodException("service-internal failure")}) {
            for (Class<?> api : new Class<?>[]{FourApi.class, BothApi.class}) {
                Service service = new Service(); service.failure = failure;
                ReaderModeCompat compat = resolve(api);
                for (int flags : new int[]{0x81, 0}) {
                    try {
                        compat.set(service, token, callback, flags, extras, "com.android.shell");
                        throw new AssertionError("service failure swallowed");
                    } catch (Exception actual) {
                        check(actual == failure, "must preserve original service exception");
                    }
                }
                check(service.fourCalls == (api == FourApi.class ? 2 : 0)
                        && service.fiveCalls == (api == FourApi.class ? 0 : 2),
                        "invocation failure must not retry another overload");
            }
        }
        System.out.println("ReaderMode four/five-argument selection, cleanup and failure tests passed");
    }
}
