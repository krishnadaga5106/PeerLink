package Core;

import FileTransfer.FileReceiver;
import FileTransfer.FileSender;
import FileTransfer.SenderMessageHandler;
import Interfaces.EventListener;
import Interfaces.SystemHandler;
import Models.MessageType;
import Models.SignalingMessage;
import Signaling.SignalingClient;
import WebRTC.P2PWebRTC;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

import static Core.AppState.*;

@Slf4j
public class Controller implements EventListener, SystemHandler {

    private P2PWebRTC webRTC;
    private SignalingClient signalingClient;
    private Scanner scanner;
    private CountDownLatch countDownLatch;
    private String username;
    private String roomCode;
    private boolean pendingSendReq;
    private boolean pendingRecReq;
    private boolean peerConnected;

    private AppState appState;

    @Setter
    private MessageHandler messageHandler;

    public void ini(P2PWebRTC webRTC, SignalingClient signalingClient, String username, Scanner scanner) {
        this.webRTC = webRTC;
        this.signalingClient = signalingClient;
        this.username = username;
        this.scanner = scanner;
    }

    public void run() throws Exception {
        appState = CONNECTING_TO_SERVER;

        String choice = creatorOrJoiner();
        if(choice.equals("/create")) creator();
        else if(choice.equals("/join")) joiner();
        else return;

        //stop the web socket
        log.info("Closing the Web Socket");
        signalingClient.stop();

        appState = CHATTING;
        peerConnected = true;
        //post webRTC establishment
        postWebRTC();
    }

    private String creatorOrJoiner(){
        printMainMenu();
        String choice = scanner.nextLine().trim();
        if(choice.equals("/exit")){
            return choice;
        } else if (!choice.equals("/create") && !choice.equals("/join")) {
            write("Invalid choice!!");
            choice = creatorOrJoiner();
        }
        return choice;
    }

    private void printMainMenu(){
        clear();
        System.out.println("\n====== P2P WebRTC CLI ======");
        System.out.println("1) Create Room   /create");
        System.out.println("2) Join Room     /join");
        System.out.println("3) Quit          /exit");
        System.out.print("Choice: ");
    }

    private void clear(){
        System.out.print("\033[H\033[2J");
    }
    
    private void creator() throws Exception {
        //send message to create room first
        SignalingMessage signalingMessage = new SignalingMessage(MessageType.CREATE);
        signalingClient.sendMessage(signalingMessage);

        //wait for user to join room
        setLatch();

        log.info("{} Room joined", username);
        write("Your Room Code: " + roomCode);
        write("Share this room code with other peer to let them join!");
        write("Waiting for other peer to join...");

        //reset
        //wait for peer to join then create offer
        setLatch();
        log.info("Peer Joined");//tryna get the peer name

        //establish the webRTC connection
        webRTC.createOffer();

        //reset
        //wait for ANSWER
        setLatch();
        log.info("Received answer from peer");
    }

    private void joiner() throws Exception {
        getRoomCode();
        //wait for user to join room
        setLatch();
        log.info("{} Room joined", username);

        //reset
        //wait for offer
        setLatch();
        log.info("Received offer from peer");
        webRTC.createAnswer();


        //now the webRTC connection is established
        //reset
        //wait for DC
        setLatch();
        log.info("DataChannel Ready.");
    }

    private void postWebRTC() throws Exception {
        clear();
        write("Connected to Peer!");// try to get the peer name later
        write("Type a message to chat, or /send to start sending ");

        while (peerConnected) {
            String line;
            try {
                System.out.print("\r> ");
                line = scanner.nextLine();
            } catch (Exception e) {
                log.error("Error reading line: {}", e.getMessage());
                break;
            }

            if (!line.startsWith("/") && peerConnected) {
                String chat = "CHAT::" + line;
                webRTC.send(ByteBuffer.wrap(chat.getBytes()), false);
            }
            else if (line.equalsIgnoreCase("/send")) {
                if (pendingSendReq)
                    write("Already a pending send request!!");
                else {
                    //send start sending req
                    write("Sending send request...");
                    webRTC.send(ByteBuffer.wrap("SYS::REQ_SEND".getBytes()), false);
                    pendingSendReq = true;
                }
            }
            else if (line.equalsIgnoreCase("/accept")) {
                if (pendingRecReq && appState == CHATTING) {
                    //send ack to start req
                    webRTC.send(ByteBuffer.wrap("SYS::ACK_SEND".getBytes()), false);
                    pendingRecReq = false;

                    appState = TRANSFERRING;

                    new Thread(() -> {
                        try {
                            startFileReceiving();
                        } catch (Exception e) {
                            log.error(e.getMessage());
                        }
                    }).start();

                }
                else if (appState == REVIEWING_FILES &&
                        messageHandler.getDataHandler() instanceof FileReceiver receiver) {
                    receiver.userAccept(true);
                }
                else {
                    write("No pending receive request!!");
                }
            }
            else if (line.equalsIgnoreCase("/deny")) {
                //send NACK to receive
                if (pendingRecReq) {
                    webRTC.send(ByteBuffer.wrap("SYS::NACK_SEND".getBytes()), false);
                    pendingRecReq = false;
                } else if (appState == REVIEWING_FILES &&
                        messageHandler.getDataHandler() instanceof FileReceiver receiver) {
                    receiver.userAccept(false);
                }
                else {
                    write("No pending receive request!!");
                }
            }
            else if (line.equalsIgnoreCase("/retry") && appState == GETTING_DIR){
                if(messageHandler.getDataHandler() instanceof FileReceiver receiver){
                    receiver.onDir(true);
                    notify();
                }
            }
            else if (line.equalsIgnoreCase("/cancel") && appState == GETTING_DIR){
                 if(messageHandler.getDataHandler() instanceof FileReceiver receiver){
                     receiver.onDir(false);
                     notify();
                 }
            }
            else if (line.equalsIgnoreCase("/exit")) {
                break;
            }
            else {
                write("Invalid command!!");
            }
        }
    }

    @Override
    public void handleMessage(String msg) {
        if(msg.startsWith("CHAT")) {
            System.out.println("\r[Peer]: " + msg.substring(6));
            System.out.print("\r> ");
        }
        else if(msg.equals("SYS::REQ_SEND")){
            //take ack from user to start receiving
            pendingRecReq = true;
            write("Peer requested to send file. " + "\n" +
                    "/accept to accept, /deny to deny");
        }
        else if(msg.equals("SYS::ACK_SEND")){

            appState = TRANSFERRING;
            write("Peer accepted transfer request. Starting Sending file...");
            try{
                new Thread(() -> {
                    try { startFileSending(); } catch (Exception e) { log.error(e.getMessage()); }
                }).start();

            }catch (Exception e){
                log.error(e.getMessage());
            }
            pendingSendReq = false;
        }
        else if(msg.equals("SYS::NACK_SEND")){
            write("Peer denied transfer request.");
            pendingSendReq = false;
        }
    }

    private void write(String string) {
        System.out.println("\r[SYSTEM]: " + string);
        System.out.print("\r> ");
    }

//    private void writeAbove(String string) {
//        //rework this logic
//        System.out.println(string);
//    }

    private void startFileSending() throws Exception {
        FileSender fileSender = new FileSender(webRTC, this);
        SenderMessageHandler senderMessageHandler = new SenderMessageHandler(fileSender);
        messageHandler.setDataHandler(senderMessageHandler);

        fileSender.start();
    }

    private void startFileReceiving() throws Exception {
        FileReceiver fileReceiver = new FileReceiver(webRTC, this);
        messageHandler.setDataHandler(fileReceiver);

        fileReceiver.start();
    }

    private void getRoomCode(){
        //get the room code
        System.out.print("\r[SYSTEM]: Enter the Room Code: ");
        roomCode = scanner.nextLine();

        //set the roomCode
        signalingClient.setRoomCode(roomCode);

        //try to join the room with the room code
        SignalingMessage signalingMessage = new SignalingMessage(MessageType.JOIN);
        signalingClient.sendMessage(signalingMessage);

    }

    private void setLatch() throws InterruptedException {
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
    }

    private void triggerLatch(){
        if(countDownLatch != null)
            countDownLatch.countDown();
    }

    @Override
    public void onRoomJoined(String roomCode) {
        this.roomCode = roomCode;
        log.info("Room Code: {}", roomCode);
        triggerLatch();
    }

    @Override
    public void onPeerJoined() {
        triggerLatch();
    }

    @Override
    public void onAnswer() {
        triggerLatch();
    }

    @Override
    public void onOffer() {
        triggerLatch();
    }

    @Override
    public void onDataChannel() {
        triggerLatch();
    }

    @Override
    public void onError(String error) {
        if(error.equalsIgnoreCase("Room does not exists")){
            System.out.println("\r\n[System]: ERROR: Room does not exists");
            System.out.print("\r> ");
            getRoomCode();
        }else if(error.equalsIgnoreCase("Room is Full")){
            System.out.println("\r\n[System]: ERROR: Room is Full");
            System.out.print("\r> ");
            getRoomCode();
        }
    }

    @Override
    public void peerLeft() {
        //prompt that the other peer left, and exit
        write("Peer Left!!");
        peerConnected = false;
        write("Press Enter to exit...");
    }

    @Override
    public void onReviewing() {
        appState = REVIEWING_FILES;
    }

    @Override
    public void onGettingDir() {
        appState = GETTING_DIR;
    }

    @Override
    public void onFileTransferComplete(){
        //prompt the user that the file transfer is completed
        System.out.println("\r[SYSTEM]: File Transfer Completed!!");
        System.out.print("\r> ");
        //now back to chat mode
        appState = CHATTING;
    }

}
