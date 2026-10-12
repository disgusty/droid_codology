package com.disgusty.droid_codology.work;

/** Implemented by the activity: makes sure folders on storage can be read; false if it had to ask. */
public interface StorageAccessBridge {
    boolean ensureStorage();
}