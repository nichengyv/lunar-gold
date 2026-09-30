package com.capsule.agentkey.client;

import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
public class OpenAiClient {
    public interface Callback {
        void onChunk(String chunk);
        void onComplete();
        void onError(Throwable t);
    }

    public void streamChat(String apiKey, String prompt, Callback callback) {
        try {
            String[] fakeChunks = ("模拟智能体回复：" + prompt + " 这是流式输出片段...").split(" ");
            for (String chunk : fakeChunks) {
                callback.onChunk(chunk + " ");
                TimeUnit.MILLISECONDS.sleep(300);
            }
            callback.onComplete();
        } catch (Exception e) {
            callback.onError(e);
        }
    }
}