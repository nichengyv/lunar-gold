package com.capsule.agentkey.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * P5-02：单条评测用例，对应 jsonl 中的一行。
 */
public record EvalCase(
        String id,
        String category,
        String fixture,
        String prompt,
        int maxRounds,
        JsonNode expect,
        JsonNode budget,
        List<ScriptedAgentClient.ScriptedTurn> script
) {
    public static EvalCase fromJson(JsonNode node) {
        String id = node.path("id").asText();
        String category = node.path("category").asText();
        String fixture = node.path("fixture").asText();
        String prompt = node.path("prompt").asText();
        int maxRounds = node.path("maxRounds").asInt(5);
        JsonNode expect = node.path("expect");
        JsonNode budget = node.path("budget");

        List<ScriptedAgentClient.ScriptedTurn> turns = new ArrayList<>();
        JsonNode scriptNode = node.path("script");
        if (scriptNode.isArray()) {
            for (JsonNode turnNode : scriptNode) {
                if (turnNode.has("toolCalls")) {
                    List<ScriptedAgentClient.ToolCallSpec> specs = new ArrayList<>();
                    for (JsonNode tc : turnNode.path("toolCalls")) {
                        specs.add(new ScriptedAgentClient.ToolCallSpec(
                                tc.path("name").asText(),
                                tc.path("arguments").asText("{}")));
                    }
                    turns.add(ScriptedAgentClient.ScriptedTurn.ofTools(specs));
                } else if (turnNode.has("text")) {
                    turns.add(ScriptedAgentClient.ScriptedTurn.ofText(turnNode.path("text").asText()));
                }
            }
        }
        return new EvalCase(id, category, fixture, prompt, maxRounds, expect, budget, turns);
    }
}
