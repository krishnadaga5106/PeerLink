package WebRTC;

import Interfaces.EventListener;
import Models.*;
import Signaling.SignalingClient;
import dev.onvoid.webrtc.*;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.Scanner;

@Slf4j
public class P2PWebRTC {

    private String username;

    private EventListener listener;

    @Setter
    private SignalingClient signalingClient;

    private final String STUN_SERVER = "stun:stun.l.google.com:19302";

    private PeerConnectionFactory factory;
    private RTCPeerConnection connection;
    private RTCDataChannel dataChannel;

    private final Scanner scanner = new Scanner(System.in);

    public void ini(){
        factory = new PeerConnectionFactory();
        RTCConfiguration config = new RTCConfiguration();
        RTCIceServer iceServer = new RTCIceServer();
        iceServer.urls.add(STUN_SERVER);
        config.iceServers.add(iceServer);

        connection = factory.createPeerConnection(config, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate rtcIceCandidate) {
                //directly send it to the other peer
                signalingClient.sendMessage(new SignalingMessage(MessageType.ICE, rtcIceCandidate.sdp));
            }

            @Override
            public void onDataChannel(RTCDataChannel dc) {
//                System.out.println("REMOTE DATA CHANNEL ARRIVED: " + dc.getLabel());
                dataChannel = dc;  // override the reference
                dc.registerObserver(new RTCDataChannelObserver() {
                    @Override
                    public void onBufferedAmountChange(long prev) { }

                    @Override
                    public void onStateChange() {
                        System.out.println("DataChanne22222222222222222222222222l state: " + dc.getState());

                        if (dc.getState() == RTCDataChannelState.OPEN) {
                            System.out.println("DataChannel OPEN");
                            listener.onDataChannel();
                        }

                    }

                    //this is not being called TRY WHAT HAPPENS AFTER REMOVING IT
                    @Override
                    public void onMessage(RTCDataChannelBuffer buffer) {

                        if (!buffer.binary) {
                            byte[] bytes = new byte[buffer.data.remaining()];
                            buffer.data.get(bytes);
                            String msg = new String(bytes);
                            System.out.println("[REMOTE upar] " + msg);
                        }else {
                            System.out.println(buffer.data.toString());
                        }
                    }
                });
            }

        });

        // CREATE A DATA CHANNEL — MANDATORY
        RTCDataChannelInit init = new RTCDataChannelInit();
        init.ordered = true;
        dataChannel = connection.createDataChannel("chat", init);

        dataChannel.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) { }

            @Override
            public void onStateChange() {
                System.out.println("DataChannel state: " + dataChannel.getState());
                if(dataChannel.getState() == RTCDataChannelState.OPEN) {
                    listener.onDataChannel();
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                if (!buffer.binary) {
                    byte[] bytes = new byte[buffer.data.remaining()];
                    buffer.data.get(bytes);
                    String msg = new String(bytes);
                    System.out.println("[REMOTE] " + msg);
                }else {
                    System.out.println(buffer.data.toString());
                }
            }
        });
    }

    public void handle(SignalingResponse signalingResponse) {
        switch (signalingResponse.getResponseType()){
            case ResponseType.ANSWER, ResponseType.OFFER -> setRemoteDescription(signalingResponse);
            case ResponseType.ICE -> addRemoteIceCandidate(signalingResponse.getMessage());
            default -> log.info("Unknown response type: {}", signalingResponse.getResponseType());
        }
    }

    public void createOffer(){
        RTCOfferOptions options = new RTCOfferOptions();

        connection.createOffer(options, new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                log.info("Successfully created Offer.");
                // Set local description
                connection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        // Send the offer to the remote peer via your signaling channel
                        SignalingMessage signalingMessage = new SignalingMessage(MessageType.OFFER, description.sdp);
//                        listener.onEvent(AppState.WAITING_FOR_ANSWER);
                        signalingClient.sendMessage(signalingMessage);
                    }

                    @Override
                    public void onFailure(String error) {
                        log.error("Failed to set local description: {}", error);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                System.err.println("Failed to create offer: " + error);
            }
        });
    }

    public void createAnswer(){
        RTCAnswerOptions options = new RTCAnswerOptions();
        connection.createAnswer(options, new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                log.info("Successfully created Answer.");
                connection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        SignalingMessage signalingMessage = new SignalingMessage(MessageType.ANSWER, description.sdp);
                        signalingClient.sendMessage(signalingMessage);
                    }

                    @Override
                    public void onFailure(String s) {
                        log.error("Failed to set local description: {}", s);
                    }
                });
            }
            @Override
            public void onFailure(String error) {
                System.err.println("Failed to create answer: " + error);
            }
        });
    }

    private void setRemoteDescription(SignalingResponse response) {
        //get the type of desc
        RTCSdpType sdpType = RTCSdpType.valueOf(response.getResponseType().name());

        String remoteSdpText = response.getMessage();

        //create new desc
        RTCSessionDescription remoteSdp = new RTCSessionDescription(sdpType, remoteSdpText);

        connection.setRemoteDescription(remoteSdp, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                log.info("Remote description set {}", sdpType);
            }
            @Override
            public void onFailure(String s) {
                log.error("Failed to set remote description: {}", s);
            }
        });
    }

    private void addRemoteIceCandidate(String ice){
        RTCIceCandidate candidate = new RTCIceCandidate("0", 0, ice);
        connection.addIceCandidate(candidate);
//        log.info("Added ICE Candidate: {}", candidate);
    }

    public void sendMessage(String msg) throws Exception {
        if (dataChannel == null) {
            System.out.println("DataChannel not created");
            return;
        }
        if (dataChannel.getState() != RTCDataChannelState.OPEN) {
            System.out.println("DataChannel is not open yet");
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes());
        RTCDataChannelBuffer buf = new RTCDataChannelBuffer(buffer, false);
        dataChannel.send(buf);
    }

    public void shutDown(){
        if(connection != null)
            connection.close();
        if(factory != null)
            factory.dispose();
        scanner.close();
    }

    public P2PWebRTC(String username, EventListener listener) {
        this.username = username;
        this.listener = listener;
    }

}
