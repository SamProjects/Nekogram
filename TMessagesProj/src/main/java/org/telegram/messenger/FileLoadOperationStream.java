package org.telegram.messenger;

public interface FileLoadOperationStream {
    void newDataAvailable();

    default boolean isDownloadCancelled() {
        return false;
    }
}
