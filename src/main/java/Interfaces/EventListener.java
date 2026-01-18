package Interfaces;

import Core.AppState;

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

    void onError(String error);

    void onFileTransferComplete(boolean success);

    void peerLeft();

    //to change the app state to reviewing and get the user input
    void onReviewing();

    void onGettingDir();

    void onPause(boolean pause);

    void setAppState(AppState appState);
}
