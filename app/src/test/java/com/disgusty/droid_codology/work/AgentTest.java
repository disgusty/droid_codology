package com.disgusty.droid_codology.work;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class AgentTest {
    @Test
    public void parsesToolCalls() {
        Agent a = new Agent(null, null);
        String reply = "Let me look first.\n<tool_call>\n{\"tool\": \"read_file\", \"args\": {\"path\": \"a.py\"}}\n</tool_call>\n"
                + "<tool_call>{\"tool\":\"search\",\"args\":{\"query\":\"TODO\"}}</tool_call>";
        List<Agent.Step> calls = a.parse(reply);
        assertEquals(2, calls.size());
        assertEquals("read_file", calls.get(0).tool);
        assertEquals("a.py", calls.get(0).args.optString("path"));
        assertEquals("TODO", calls.get(1).args.optString("query"));
        assertEquals("Let me look first.", Agent.visible(reply));
    }

    @Test
    public void acceptsFencedJsonAndOtherKeys() {
        Agent a = new Agent(null, null);
        List<Agent.Step> calls = a.parse("```json\n{\"tool\": \"list_files\", \"arguments\": {\"depth\": 1}}\n```");
        assertEquals(1, calls.size());
        assertEquals(1, calls.get(0).args.optInt("depth"));
        assertTrue(a.parse("Just an answer with {braces} and no calls.").isEmpty());
        assertTrue(a.parse("<tool_call>{not json}</tool_call>").isEmpty());
    }

    @Test
    public void hidesCallsWhileStreaming() {
        assertEquals("Reading", Agent.visible("Reading\n<tool_call>\n{\"tool\": \"read_f"));
    }
}
