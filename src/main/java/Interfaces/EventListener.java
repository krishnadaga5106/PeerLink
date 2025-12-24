package Interfaces;

public interface EventListener {

    //sender-get the roomCode setup
    void onRoomJoined(String roomCode);

    //ACTUAL START OF THE WEBRTC
    //send the offer
    void onPeerJoined();

    //for logging and getting to know the state of the program
    void onAnswer();

    //signaling done, close the socket
    void onDataChannel();

    //for logging and state info
    void onOffer();

}
