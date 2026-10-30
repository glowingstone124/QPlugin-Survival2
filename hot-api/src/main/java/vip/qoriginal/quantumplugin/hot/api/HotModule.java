package vip.qoriginal.quantumplugin.hot.api;

/** Trusted business code. Preparation must not mutate Bukkit or start unmanaged work. */
public interface HotModule extends AutoCloseable {
    int API_VERSION = 2;
    void prepare(ModuleContext context) throws Exception;
    /** Runs on the server thread immediately after activation, before the next tick. */
    default void healthCheck() throws Exception { }
    /** Runs on the server thread after use; failed preparation may close on its preparation thread. */
    @Override default void close() throws Exception { }
}
