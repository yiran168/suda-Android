/*
 * Android compatibility replacement for Apache POI's desktop-only mapped-buffer cleaner.
 *
 * Apache POI uses MethodHandle.invokeExact to eagerly unmap desktop JVM MappedByteBuffers. That
 * bytecode cannot be dexed below API 26 and is unnecessary here because the app imports legacy
 * Office documents from bounded InputStreams. Android's runtime will reclaim those buffers.
 */
package org.apache.poi.poifs.nio;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class CleanerUtil {
    public static final boolean UNMAP_SUPPORTED = false;
    public static final String UNMAP_NOT_SUPPORTED_REASON =
            "Explicit mapped-buffer unmapping is disabled on Android";

    private CleanerUtil() {
    }

    public static BufferCleaner getCleaner() {
        return null;
    }

    public interface BufferCleaner {
        void freeBuffer(ByteBuffer buffer) throws IOException;
    }
}
