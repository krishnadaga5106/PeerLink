package WebRTC;

import Interfaces.EventListener;
import Core.MessageHandler;
import Models.*;
import Signaling.SignalingClient;
import dev.onvoid.webrtc.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;

@Slf4j
@RequiredArgsConstructor
public class P2PWebRTC {


    // TODO: check if the peer is sender or receiver? if sender then on create Data Channel

    private final EventListener listener;
    @Setter
    private SignalingClient signalingClient;
    private final MessageHandler messageHandler;

    private final String STUN_SERVER = "stun:stun.l.google.com:19302";

    private PeerConnectionFactory factory;
    private RTCPeerConnection connection;
    @Getter
    private RTCDataChannel dataChannel;

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
                dataChannel = dc;  // override the reference
                setupDataChannelObserver(dc);
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
        RTCDataChannelInit init = new RTCDataChannelInit();
        init.ordered = true;
        this.dataChannel = connection.createDataChannel("channel", init);

        setupDataChannelObserver(this.dataChannel);
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
                log.error("Failed to create offer: {}", error);
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
                log.error("Failed to create answer: {}", error);
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

    private void setupDataChannelObserver(RTCDataChannel dc) {

        dc.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long prev) { }

            @Override
            public void onStateChange() {
                if (dc.getState() == RTCDataChannelState.OPEN) {
                    log.info("Data channel opened.");
                    listener.onDataChannel();
                }else if(dc.getState() == RTCDataChannelState.CLOSED){
                    listener.peerLeft();
                }
            }
            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                messageHandler.handle(buffer);
            }
        });
    }

    public void send(ByteBuffer buffer, boolean binary) throws Exception {
        if(dataChannel == null) {
            log.error("DataChannel not created yet.");
            System.out.println("\r[SYSTEM]: Something went wrong...");
            System.exit(1);
            return;
        }
        if (dataChannel.getState() != RTCDataChannelState.OPEN) {
            //peer was once connected and is not now
            log.error("DataChannel is not open.");
            return;
        }
        RTCDataChannelBuffer buf = new RTCDataChannelBuffer(buffer, binary);
        dataChannel.send(buf);
    }

    public void shutDown(){
        if(connection != null) {
            connection.close();
            connection = null;
        }
        if(factory != null) {
            factory.dispose();
            factory = null;
        }
    }
}
