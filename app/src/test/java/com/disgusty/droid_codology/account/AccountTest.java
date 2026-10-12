package com.disgusty.droid_codology.account;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccountTest {
    @Test
    public void homeNetworkAddressesAreLocal() {
        assertTrue(Account.isLocalHost("192.168.15.61:8080"));
        assertTrue(Account.isLocalHost("https://192.168.1.2"));
        assertTrue(Account.isLocalHost("10.0.2.2:8080/"));
        assertTrue(Account.isLocalHost("172.20.0.5"));
        assertTrue(Account.isLocalHost("localhost:8080"));
        assertTrue(Account.isLocalHost("127.0.0.1"));
        assertTrue(Account.isLocalHost("my-pc.local:8080"));
    }

    @Test
    public void publicAddressesAreNot() {
        assertFalse(Account.isLocalHost("dc.example.com"));
        assertFalse(Account.isLocalHost("https://172.32.0.1"));
        assertFalse(Account.isLocalHost("8.8.8.8:443"));
        assertFalse(Account.isLocalHost("192.169.0.1"));
    }
}
