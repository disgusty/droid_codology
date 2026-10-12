package com.disgusty.droid_codology.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class SyncAndTokenTest {
    @Test
    public void syncHashIgnoresKeyOrder() throws Exception {
        JSONObject a = new JSONObject("{\"b\":1,\"a\":{\"y\":[1,2.0,\"x\"],\"x\":true}}");
        JSONObject b = new JSONObject("{\"a\":{\"x\":true,\"y\":[1,2,\"x\"]},\"b\":1.0}");
        assertEquals(Account.hash(a), Account.hash(b));
        assertNotEquals(Account.hash(a), Account.hash(new JSONObject("{\"a\":{\"x\":true,\"y\":[2,1,\"x\"]},\"b\":1}")));
        assertEquals(32, Account.hash(a).length());
    }

    @Test
    public void findsGitHubTokens() {
        String classic = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
        assertEquals(classic, GitHubAccount.findToken("  " + classic + "\n"));
        assertEquals(classic, GitHubAccount.findToken("token: " + classic + " (copy it now)"));
        String fine = "github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123";
        assertEquals(fine, GitHubAccount.findToken(fine));
        assertNull(GitHubAccount.findToken("hello world"));
        assertNull(GitHubAccount.findToken("ghp_short"));
    }
}
