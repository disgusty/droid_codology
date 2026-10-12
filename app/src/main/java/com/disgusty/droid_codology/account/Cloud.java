package com.disgusty.droid_codology.account;

import java.io.IOException;

/**
 * An account that keeps settings in the user's own cloud storage, so no droid_codology server is
 * needed: Google (Drive), GitHub (a secret Gist) or email (Firebase). One is used at a time.
 * Network methods block; call them off the UI thread.
 */
public interface Cloud {
    boolean signedIn();

    /** Name and/or email of the signed-in user. */
    String displayName();

    /** Where settings are kept, for the UI ("Google Drive", "a private GitHub Gist"...). */
    String storage();

    /** Same contract as {@link Account#sync}: newer remote data wins, else local data is uploaded. */
    String sync(Account.SyncData data, boolean forceUpload) throws IOException;

    void signOut();
}
