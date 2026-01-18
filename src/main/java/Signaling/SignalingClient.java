package Signaling;

import Interfaces.EventListener;
import Models.SignalingMessage;
import Models.SignalingResponse;
import WebRTC.P2PWebRTC;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

@Slf4j
public class SignalingClient {

    private final EventListener listener;

    private final String url = "ws://localhost:8080/ws";
    private WebSocketClient client;
    private SignalingSocket socket;
    private P2PWebRTC webRTC;
    private final ObjectMapper objectMapper;

    @Setter
    private String username;
    @Setter @Getter
    private String roomCode;

    public SignalingClient(EventListener listener, P2PWebRTC webRTC) {
        this.client = new WebSocketClient();
        this.socket = new SignalingSocket(this);
        this.listener = listener;
        this.webRTC = webRTC;
        this.objectMapper = new ObjectMapper();
    }

    public void connect() throws Exception {
        client.start();
        client.connect(socket, URI.create(url)).get();

        log.info("Client connected to {}", url);
    }

    public void handle(String message) {
        SignalingResponse signalingResponse = objectMapper.readValue(message, SignalingResponse.class);

        switch (signalingResponse.getResponseType()){
            case INFO -> log.info(signalingResponse.getMessage());
            case ERROR -> onError(signalingResponse);
            case PEER_JOIN -> listener.onPeerJoined();
            case OFFER -> onOffer(signalingResponse);
            case ANSWER -> onAnswer(signalingResponse);
            case ICE -> webRTC.handle(signalingResponse);
            case JOINED -> joinRoom(signalingResponse);
        }
    }


    private void onAnswer(SignalingResponse signalingResponse) {
        webRTC.handle(signalingResponse);
        listener.onAnswer();
    }

    private void onOffer(SignalingResponse signalingResponse) {
        webRTC.handle(signalingResponse);
        listener.onOffer();
    }

    private void joinRoom(SignalingResponse signalingResponse) {
        setRoomCode(signalingResponse.getMessage());
        listener.onRoomJoined(signalingResponse.getMessage());
    }

    private void onError(SignalingResponse signalingResponse) {
        log.error(signalingResponse.getMessage());
        listener.onError(signalingResponse.getMessage());
    }

    public void sendMessage(SignalingMessage signalingMessage) {
        signalingMessage.setRoomCode(roomCode);
        signalingMessage.setUsername(username);
        String message = objectMapper.writeValueAsString(signalingMessage);
        try {
            socket.sendMessage(message);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void stop() throws Exception {
        if(client != null) {
            client.stop();
        }
    }

}
