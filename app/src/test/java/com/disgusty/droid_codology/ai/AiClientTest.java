package com.disgusty.droid_codology.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AiClientTest {
    private static String read(String kind, String sse) throws IOException {
        final StringBuilder deltas = new StringBuilder();
        String all = AiClient.readStream(kind, new ByteArrayInputStream(sse.getBytes("UTF-8")), new AiClient.Listener() {
            @Override
            public void onDelta(String text) {
                deltas.append('|').append(text);
            }
        }, null);
        return all + " " + deltas;
    }

    @Test
    public void openAiStream() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                + ": keep-alive\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n"
                + "data: [DONE]\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"ignored\"}}]}\n\n";
        assertEquals("Hello |Hel|lo", read(AiProvider.OPENAI, sse));
    }

    @Test
    public void anthropicStream() throws Exception {
        String sse = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Привет\"}}\n\n"
                + "event: ping\ndata: {\"type\":\"ping\"}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"!\"}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        assertEquals("Привет! |Привет|!", read(AiProvider.ANTHROPIC, sse));
    }

    @Test
    public void geminiAndServerStreams() throws Exception {
        String g = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"a\"},{\"text\":\"b\"}]}}]}\r\n\r\n"
                + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"c\"}]}}],\"usageMetadata\":{}}\r\n\r\n";
        assertEquals("abc |ab|c", read(AiProvider.GEMINI, g));
        String s = "data: {\"delta\":\"x\"}\n\ndata: {\"delta\":\"y\"}\n\ndata: {\"done\":true,\"usage\":3}\n\n";
        assertEquals("xy |x|y", read(AiProvider.SERVER, s));
    }

    @Test
    public void streamErrorsSurface() throws Exception {
        try {
            read(AiProvider.ANTHROPIC, "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n");
            fail();
        } catch (IOException e) {
            assertEquals("Overloaded", e.getMessage());
        }
        assertEquals("Invalid API key", AiClient.errorMessage(401, "{\"error\":{\"message\":\"Invalid API key\"}}"));
        assertEquals("Daily AI limit reached", AiClient.errorMessage(429, "{\"error\":\"Daily AI limit reached\"}"));
        assertEquals("HTTP 502: Bad gateway", AiClient.errorMessage(502, "Bad gateway"));
        assertEquals("HTTP 504: 504 Gateway Time-out nginx", AiClient.errorMessage(504,
                "<html><head><title>504 Gateway Time-out</title></head><body><center><h1>504 Gateway Time-out</h1></center><hr><center>nginx</center></body></html>"));
    }

    @Test
    public void requestShapes() throws Exception {
        List<AiClient.Message> msgs = new ArrayList<AiClient.Message>(Arrays.asList(
                new AiClient.Message("user", "hi"), new AiClient.Message("assistant", "hello"), new AiClient.Message("user", "q")));
        AiProvider an = new AiProvider("a", "A", AiProvider.ANTHROPIC, "https://api.anthropic.com/", "k1");
        AiClient.Request r = AiClient.build(an, "m", "sys", msgs, 0, null);
        assertEquals("https://api.anthropic.com/v1/messages", r.url);
        assertEquals("k1", r.headers.get("x-api-key"));
        assertEquals("sys", r.body.getString("system"));
        assertEquals(3, r.body.getJSONArray("messages").length());

        AiProvider oa = new AiProvider("o", "O", AiProvider.OPENAI, "https://api.openai.com/v1", "k2");
        r = AiClient.build(oa, "gpt", "sys", msgs, 100, null);
        assertEquals("https://api.openai.com/v1/chat/completions", r.url);
        assertEquals("Bearer k2", r.headers.get("Authorization"));
        assertEquals("system", r.body.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals(100, r.body.getInt("max_tokens"));

        AiProvider ge = new AiProvider("g", "G", AiProvider.GEMINI, "https://generativelanguage.googleapis.com", "k3");
        r = AiClient.build(ge, "gemini-x", null, msgs, 0, null);
        assertTrue(r.url.endsWith("/v1beta/models/gemini-x:streamGenerateContent?alt=sse"));
        assertEquals("model", r.body.getJSONArray("contents").getJSONObject(1).getString("role"));

        AiProvider sv = new AiProvider("s", "S", AiProvider.SERVER, "https://dc.example.com", "");
        r = AiClient.build(sv, "an/c1", null, msgs, 0, "dcs_tok");
        assertEquals("https://dc.example.com/v1/ai/chat", r.url);
        assertEquals("Bearer dcs_tok", r.headers.get("Authorization"));
    }
}
