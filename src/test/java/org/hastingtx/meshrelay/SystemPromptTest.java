package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SystemPrompt builder — verifies prompt content is correct
 * and adapts to different node configurations and processor types.
 */
class SystemPromptTest {

    /** Build a minimal PeerConfig for testing by writing a temp config file. */
    private PeerConfig makeConfig(String json) throws IOException {
        // PeerConfig.load() needs a file and an OPENBRAIN_KEY env var.
        // We can't easily call load() without OpenBrain, so we test
        // SystemPrompt.build() using the Json-based config parsing directly.
        // This mirrors what parseConfig does internally.
        Json cfg = Json.parse(json);
        // We'll just test the prompt builder accepts the right inputs
        return null; // Can't construct PeerConfig directly (private constructor)
    }

    @Nested
    class LocalProcessor {

        @Test
        void promptContainsNodeIdentity() {
            // Use a config JSON and parse the fields we need to verify
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("**linuxserver**"));
            assertTrue(prompt.contains("this node"));
        }

        @Test
        void promptContainsSenderInfo() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("from **macmini**"));
        }

        @Test
        void promptContainsMeshNetworkSection() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("## Mesh Network"));
            assertTrue(prompt.contains("messenger daemon"));
            assertTrue(prompt.contains("OpenBrain"));
        }

        @Test
        void promptContainsNodeRoles() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("linuxserver"));
            assertTrue(prompt.contains("macmini"));
            assertTrue(prompt.contains("macbook-air"));
            assertTrue(prompt.contains("Ubuntu Linux"));
            assertTrue(prompt.contains("Ollama"));
        }

        @Test
        void promptContainsOpenBrainSection() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("## OpenBrain"));
            assertTrue(prompt.contains("browse_thoughts"));
            assertTrue(prompt.contains("search_thoughts"));
            assertTrue(prompt.contains("capture_thought"));
            assertTrue(prompt.contains("x-brain-key"));
        }

        @Test
        void promptContainsOpenBrainUrl() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("http://192.168.0.226:3000"));
        }

        @Test
        void localProcessorHasToolAccess() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("local tool access"));
            assertTrue(prompt.contains("bash"));
            assertTrue(prompt.contains("MCP tools"));
        }

        @Test
        void promptContainsGuidelines() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("## Guidelines"));
            assertTrue(prompt.contains("concise"));
            assertTrue(prompt.contains("OpenBrain"));
            assertTrue(prompt.contains("Richard"));
        }

        @Test
        void promptListsPeers() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            // The test config has macmini as a peer
            assertTrue(prompt.contains("macmini"));
        }
    }

    @Nested
    class TextOnlyProcessor {

        @Test
        void textOnlyIsConstrained() {
            String prompt = buildTestPrompt("macmini", "linuxserver", 500, false);
            assertTrue(prompt.contains("RULES:"));
            assertTrue(prompt.contains("ONLY what was asked"));
            assertTrue(prompt.contains("Do NOT ask follow-up"));
            assertTrue(prompt.contains("Do NOT suggest next steps"));
        }

        @Test
        void textOnlyHasNoMeshContext() {
            String prompt = buildTestPrompt("macmini", "linuxserver", 500, false);
            assertFalse(prompt.contains("## Mesh Network"));
            assertFalse(prompt.contains("## OpenBrain"));
            assertFalse(prompt.contains("browse_thoughts"));
        }

        @Test
        void textOnlyHasNoPersonality() {
            String prompt = buildTestPrompt("macmini", "linuxserver", 500, false);
            assertFalse(prompt.contains("## Your Personality"));
            assertFalse(prompt.contains("echo chamber"));
        }

        @Test
        void textOnlyIdentifiesNodeAndSender() {
            String prompt = buildTestPrompt("macmini", "linuxserver", 500, false);
            assertTrue(prompt.contains("macmini"));
            assertTrue(prompt.contains("linuxserver"));
            assertTrue(prompt.contains("thread #500"));
        }

        @Test
        void textOnlyIsShort() {
            String prompt = buildTestPrompt("macmini", "linuxserver", 500, false);
            // Constrained prompt should be much shorter than the full one
            String fullPrompt = buildTestPrompt("macmini", "linuxserver", 500, true);
            assertTrue(prompt.length() < fullPrompt.length() / 2,
                "Constrained prompt (" + prompt.length() + " chars) should be less than half "
                + "the full prompt (" + fullPrompt.length() + " chars)");
        }
    }

    @Nested
    class Personality {

        @Test
        void linuxserverIsConservative() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("careful engineer"));
            assertTrue(prompt.contains("question assumptions"));
        }

        @Test
        void macminiIsRiskTaker() {
            String prompt = buildTestPrompt("macmini", "linuxserver", 462, true);
            assertTrue(prompt.contains("action"));
            assertTrue(prompt.contains("risk"));
        }

        @Test
        void macbookAirIsContrarian() {
            String prompt = buildTestPrompt("macbook-air", "linuxserver", 462, true);
            assertTrue(prompt.contains("contrarian"));
            assertTrue(prompt.contains("tenth man"));
        }

        @Test
        void unknownNodeGetsDefaultPersonality() {
            String prompt = buildTestPrompt("unknown-node", "linuxserver", 462, true);
            // The "Your Personality" section should have the default personality
            assertTrue(prompt.contains("direct"));
            // The node roles section still mentions all nodes' personalities
            // but the agent's own personality should be the generic default
            assertTrue(prompt.contains("no hand-holding"));
        }

        @Test
        void personalitiesAreDifferent() {
            String linux = buildTestPrompt("linuxserver", "macmini", 1, true);
            String mac = buildTestPrompt("macmini", "linuxserver", 1, true);
            String air = buildTestPrompt("macbook-air", "linuxserver", 1, true);
            // Each should have unique personality text
            assertFalse(linux.contains("ship it and iterate"));
            assertTrue(mac.contains("ship it and iterate"));
            assertTrue(air.contains("devil's advocate"));
            assertFalse(linux.contains("devil's advocate"));
        }

        @Test
        void promptEncouragesDisagreement() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("echo chamber"));
            assertTrue(prompt.contains("Challenge"));
        }

        @Test
        void promptMentionsHumor() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("Humor"));
        }

        @Test
        void nodeRolesIncludePersonalityHints() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("conservative engineer"));
            assertTrue(prompt.contains("pragmatic risk-taker"));
            assertTrue(prompt.contains("contrarian"));
        }
    }

    @Nested
    class ConversationThread {

        @Test
        void promptContainsThreadId() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("thread **#462**"));
        }

        @Test
        void promptExplainsGetThread() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("get_thread"));
            assertTrue(prompt.contains("\"thread_id\": 462"));
        }

        @Test
        void promptListsMessagingTools() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("get_inbox"));
            assertTrue(prompt.contains("get_message"));
            assertTrue(prompt.contains("send_message"));
            assertTrue(prompt.contains("mark_delivered"));
            assertTrue(prompt.contains("mark_archived"));
        }

        @Test
        void promptExplainsTwoStorageLayers() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("Thoughts"));
            assertTrue(prompt.contains("Messages"));
        }

        @Test
        void threadIdDiffersPerConversation() {
            String prompt1 = buildTestPrompt("linuxserver", "macmini", 100, true);
            String prompt2 = buildTestPrompt("linuxserver", "macmini", 999, true);
            assertTrue(prompt1.contains("thread **#100**"));
            assertTrue(prompt2.contains("thread **#999**"));
            assertFalse(prompt1.contains("thread **#999**"));
        }

        @Test
        void guidelinesReferenceThreadHistory() {
            String prompt = buildTestPrompt("linuxserver", "macmini", 462, true);
            assertTrue(prompt.contains("get_thread"));
            assertTrue(prompt.contains("thread history"));
        }
    }

    @Nested
    class SummarizePrompt {

        @Test
        void summarizePromptIsConcise() {
            String prompt = SystemPrompt.summarize();
            assertTrue(prompt.contains("Summarize"));
            assertTrue(prompt.contains("2-3 sentences"));
        }

        @Test
        void summarizePromptRequestsPastTense() {
            String prompt = SystemPrompt.summarize();
            assertTrue(prompt.contains("past tense"));
        }

        @Test
        void summarizePromptMentionsPendingItems() {
            String prompt = SystemPrompt.summarize();
            assertTrue(prompt.contains("pending"));
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────

    /**
     * Build a test prompt by writing a temp config and loading it.
     * Since PeerConfig has a private constructor and load() needs OpenBrain,
     * we write a config file with openbrain_key and use a temp path.
     */
    private String buildTestPrompt(String nodeName, String fromNode,
                                    long threadId, boolean isLocal) {
        try {
            String json = """
                {
                  "node_name": "%s",
                  "openbrain_url": "http://192.168.0.226:3000",
                  "openbrain_key": "test-key-for-unit-tests",
                  "listen_port": 13007,
                  "peers": [
                    {"name": "macmini", "url": "http://192.168.0.226:13007"},
                    {"name": "macbook-air", "url": "http://192.168.0.62:13007"},
                    {"name": "linuxserver", "url": "http://192.168.0.225:13007"}
                  ]
                }
                """.formatted(nodeName);

            Path tmp = Files.createTempFile("messenger-test-", ".json");
            Files.writeString(tmp, json);
            try {
                PeerConfig config = PeerConfig.load(tmp);
                return SystemPrompt.build(config, fromNode, threadId, isLocal);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            fail("Failed to build test prompt: " + e.getMessage());
            return null;
        }
    }
}
