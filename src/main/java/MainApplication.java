import Interfaces.EventListener;
import Models.MessageType;
import Models.SignalingMessage;
import Signaling.SignalingClient;
import WebRTC.P2PWebRTC;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

@Slf4j
public class MainApplication implements EventListener {

    static Scanner scanner = new Scanner(System.in);

    @Setter
    public static String roomCode;
    public static String username;

    private static P2PWebRTC webRTC;
    private static SignalingClient signalingClient;

    private static CountDownLatch countDownLatch;

    public static void main(String[] args) throws Exception {
        new MainApplication().run();
    }

    public void run() throws Exception {
        System.out.print("Enter username: ");
        username = scanner.nextLine();

        webRTC = new P2PWebRTC(username, this);
        signalingClient = new SignalingClient(this, webRTC);
        webRTC.setSignalingClient(signalingClient);

        signalingClient.connect();
        webRTC.ini();

        signalingClient.setUsername(username);

        String choice = "";
        while(!choice.equals("3")) {
            printMenu();
            choice = scanner.nextLine().trim();
            switch(choice){
                case "1":
                    sender();
                    break;
                case "2":
                    receiver();
                    break;
                case "3":
                    break;
                default:
                    log.error("Invalid choice");
            }
        }

        //stop the app
        signalingClient.stop();
        webRTC.shutDown();
    }

    static void printMenu(){
        System.out.println("\n====== P2P WebRTC CLI ======");
        System.out.println("1) Create Offer (acts as offerer / often sender)");
        System.out.println("2) Create Answer (acts as answerer / often receiver)");
        System.out.println("3) Quit");
        System.out.print("Choice: ");
    }

    private static void sender() throws Exception {
        //send message to create room first
        SignalingMessage signalingMessage = new SignalingMessage(MessageType.CREATE);
        signalingClient.sendMessage(signalingMessage);

        //wait for user to join room
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
        log.info("{} Room joined", username);

        //reset
        //wait for peer to join then create offer
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
        log.info("Peer Joined");//tryna get the peer name
        webRTC.createOffer();

        //reset
        //wait for ANSWER
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
        log.info("Received answer from peer");

        //reset
        //wait for DC
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
        log.info("DataChannel Ready, Closing the web socket connection");

        //try to close the web socket
        signalingClient.stop();

        startChatting();
    }

    private static void receiver() throws Exception {
        //get the room code first
        System.out.print("Enter the Room Code: ");
        roomCode = scanner.nextLine();


        //set the roomCode
        signalingClient.setRoomCode(roomCode);
        //TODO: HANDLE ERROR IF ROOM CODE IS INVALID

        //try to join the room with the room code
        SignalingMessage signalingMessage = new SignalingMessage(MessageType.JOIN);
        signalingClient.sendMessage(signalingMessage);

        //wait for user to join room
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
        log.info("{} Room joined", username);

        //reset
        //wait for offer
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
        log.info("Received offer from peer");
        webRTC.createAnswer();

        //reset
        //wait for DC
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
        log.info("DataChannel Ready, Closing the web socket connection");

        //try to close the web socket
        signalingClient.stop();

        startChatting();

    }

    private static void startChatting() throws Exception {
        String choice = "";
        while(!choice.equals("exit")) {
            System.out.print("Enter Message: ");
            choice = scanner.nextLine().trim();
            webRTC.sendMessage(choice);
        }
    }

    @Override
    public void onRoomJoined(String roomCode) {
        if(roomCode != null)
            this.roomCode = roomCode;
        log.info("Room Code: {}", roomCode);
        if(countDownLatch != null)
            countDownLatch.countDown();
    }

    @Override
    public void onPeerJoined() {
        if(countDownLatch != null)
            countDownLatch.countDown();
    }

    @Override
    public void onAnswer() {
        if(countDownLatch != null)
            countDownLatch.countDown();
    }

    @Override
    public void onOffer() {
        if (countDownLatch != null)
            countDownLatch.countDown();
    }

    @Override
    public void onDataChannel() {
        if(countDownLatch != null)
            countDownLatch.countDown();
    }
}
