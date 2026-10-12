package com.disgusty.droid_codology.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public class KeyLinkTest {
    private static final String ANT = "sk-ant-api03-" + "aB3_x-".replace("-", "") + "0123456789abcdefghijKLMNOP-qr_st";
    private static final String OAI = "sk-proj-" + "Zx9YwV8uT7sR6qP5oN4mL3kJ2iH1gF0e";
    private static final String GEM = "AIza" + "SyD-0123456789abcdefghijklmnopqrstu";
    private static final String ORT = "sk-or-v1-" + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void findsKeysOfEachService() {
        assertEquals(ANT, KeyLink.CLAUDE.findKey("Your key:\n" + ANT + "\n"));
        assertEquals(OAI, KeyLink.OPENAI.findKey(" " + OAI));
        assertEquals(GEM, KeyLink.GEMINI.findKey(GEM));
    }

    @Test
    public void doesNotMistakeOtherServicesKeys() {
        assertNull(KeyLink.OPENAI.findKey(ANT));
        assertNull(KeyLink.OPENAI.findKey(ORT));
        assertNull(KeyLink.CLAUDE.findKey(OAI));
        assertNull(KeyLink.GEMINI.findKey("hello world"));
        assertNull(KeyLink.CLAUDE.findKey(null));
    }

    @Test
    public void anthropicModelsKeepOrder() throws Exception {
        JSONObject o = new JSONObject("{\"data\":[{\"id\":\"claude-opus-5-5\"},{\"id\":\"claude-sonnet-5-5\"},{\"id\":\"other\"}]}");
        assertEquals(Arrays.asList("claude-opus-5-5", "claude-sonnet-5-5"), KeyLink.filterModels(AiProvider.ANTHROPIC, "anthropic", o));
    }

    @Test
    public void openAiDropsNonChatModels() throws Exception {
        JSONObject o = new JSONObject("{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"text-embedding-3-small\"},{\"id\":\"gpt-4o-audio-preview\"},"
                + "{\"id\":\"o3\"},{\"id\":\"dall-e-3\"},{\"id\":\"gpt-4o-mini\"}]}");
        assertEquals(Arrays.asList("o3", "gpt-4o-mini", "gpt-4o"), KeyLink.filterModels(AiProvider.OPENAI, "openai", o));
    }

    @Test
    public void geminiKeepsGenerateContentModels() throws Exception {
        JSONObject o = new JSONObject("{\"models\":["
                + "{\"name\":\"models/gemini-2.5-pro\",\"supportedGenerationMethods\":[\"generateContent\",\"countTokens\"]},"
                + "{\"name\":\"models/text-embedding-004\",\"supportedGenerationMethods\":[\"embedContent\"]},"
                + "{\"name\":\"models/gemini-2.5-flash-preview-tts\",\"supportedGenerationMethods\":[\"generateContent\"]}]}");
        assertEquals(Arrays.asList("gemini-2.5-pro"), KeyLink.filterModels(AiProvider.GEMINI, "gemini", o));
    }
}
