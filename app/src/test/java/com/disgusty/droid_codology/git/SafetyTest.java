package com.disgusty.droid_codology.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SafetyTest {
    @Test
    public void refNames() {
        assertTrue(Repo.validRefName("refs/heads/main"));
        assertTrue(Repo.validRefName("refs/heads/feature/x-1"));
        assertTrue(Repo.validRefName("refs/tags/v1.0"));
        assertFalse(Repo.validRefName("refs/heads/../../config"));
        assertFalse(Repo.validRefName("refs/heads/a..b"));
        assertFalse(Repo.validRefName("HEAD"));
        assertFalse(Repo.validRefName("refs/heads/x.lock"));
        assertFalse(Repo.validRefName("refs/heads/.hidden"));
        assertFalse(Repo.validRefName("refs/heads/a b"));
        assertFalse(Repo.validRefName("refs/heads/a\\b"));
        assertFalse(Repo.validRefName("refs/heads/"));
        assertFalse(Repo.validRefName(null));
    }

    @Test
    public void credentialsLeaveTheUrl() {
        assertEquals("https://github.com/u/r.git", Remote.withoutCredentials("https://user:tok%40en@github.com/u/r.git"));
        assertEquals("https://host/x", Remote.withoutCredentials("https://host/x/"));
        // An "@" later in the path is not user info.
        assertEquals("https://host/a@b", Remote.withoutCredentials("https://host/a@b"));
    }
}
