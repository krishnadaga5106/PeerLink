package Signaling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;

@Slf4j
@RequiredArgsConstructor
public class SignalingSocket extends WebSocketAdapter {
    private final SignalingClient signalingClient;

    @Override
    public void onWebSocketConnect(Session session) {
        super.onWebSocketConnect(session);
        log.info("Web Socket Connected.");
    }

    @Override
    public void onWebSocketText(String message) {
//        log.info("Received message: {}", message);
        signalingClient.handle(message);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        super.onWebSocketClose(statusCode, reason);
        log.info("Web Socket Closed, Reason: {}", reason);
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        cause.printStackTrace();
    }

    public void sendMessage(String message) throws IOException {
        if(!isConnected()) {
            throw new IllegalStateException("WebSocket is not connected");
        }
        getSession().getRemote().sendString(message);
    }
}
