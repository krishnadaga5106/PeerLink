import FileTransfer.FileReceiver;
import FileTransfer.FileSender;
import FileTransfer.SenderMessageHandler;
import Interfaces.EventListener;
import Models.MessageType;
import Models.SignalingMessage;
import Signaling.SignalingClient;
import WebRTC.P2PWebRTC;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

@Slf4j
public class MainApplication implements EventListener {

    static Scanner scanner = new Scanner(System.in);

    @Setter
    private static String roomCode;
    private static String username;
    @Getter
    private static boolean isSender;
    private static boolean isOtherPeerSender;

    private static P2PWebRTC webRTC;
    private static SignalingClient signalingClient;

    private static CountDownLatch countDownLatch;

    public static void main(String[] args) throws Exception {
        new MainApplication().run();
    }

    public void run() throws Exception {
        System.out.print("Enter username: ");
        username = scanner.nextLine();

        String choice = "";
        while(!choice.equals("3")) {
            printMenu();
            choice = scanner.nextLine().trim();
            switch(choice){
                case "1":
                    creator();
                    break;
                case "2":
                    joiner();
                    break;
                case "3":
                    System.out.println("Exiting...");
                    break;
                default:
                    System.out.println("Invalid choice!!");
            }
        }

        //stop the app
        signalingClient.stop();
        webRTC.shutDown();
    }

    //to be checked
    private void ini() throws Exception {
        webRTC = new P2PWebRTC(username, this);
        signalingClient = new SignalingClient(this, webRTC);
        webRTC.setSignalingClient(signalingClient);

        signalingClient.connect();
        webRTC.ini();

        signalingClient.setUsername(username);
    }

    static void printMenu(){
        System.out.println("\n====== P2P WebRTC CLI ======");
        System.out.println("1) Create Room   (1)");
        System.out.println("2) Join Room     (2)");
        System.out.println("3) Quit          (3)");
        System.out.print("Choice: ");
    }

    private void creator() throws Exception {
        ini();

        //send message to create room first
        SignalingMessage signalingMessage = new SignalingMessage(MessageType.CREATE);
        signalingClient.sendMessage(signalingMessage);

        //wait for user to join room
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
        log.info("{} Room joined", username);
        System.out.println("Your Room Code: " + roomCode);
        System.out.println("Share this room code with other peer to let them join!");
        System.out.println("Waiting for other peer to join...");

        //reset
        //wait for peer to join then create offer
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
        log.info("Peer Joined");//tryna get the peer name

        //negotiate roles at the start
        negotiateRole();

        //THIS IS THE POINT WHERE WE NEED TO CHECK IF WE ARE SENDER OR
        //RECEIVER TO SEND OFFER OR WAIT FOR OFFER

        if(isSender)
            sender();
        else
            receiver();
    }

    private void joiner() throws Exception {
        ini();

        getRoomCode();
        //wait for user to join room
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();
        log.info("{} Room joined", username);

        //negotiate roles at the start after joining the room
        negotiateRole();

        //THIS IS THE POINT WHERE WE NEED TO CHECK IF WE ARE SENDER OR
        //RECEIVER TO SEND OFFER OR WAIT FOR OFFER

        if (isSender)
            sender();
        else
            receiver();
    }

    private void negotiateRole() throws Exception {
        senderOrReceiver();
        signalingClient.sendMessage
                (new SignalingMessage(MessageType.ROLE, String.valueOf(isSender)));

        System.out.println("Waiting for other peer to choose...");
        //wait for other peers message
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();

        while(isSender == isOtherPeerSender){
            System.out.println("Both selected the same choice!!");
            senderOrReceiver();
            signalingClient.sendMessage
                    (new SignalingMessage(MessageType.ROLE, String.valueOf(isSender)));

            System.out.println("Waiting for other peer to choose...");
            //wait for other peers message
            countDownLatch = new CountDownLatch(1);
            countDownLatch.await();
        }
        webRTC.setSender(isSender);
    }

    private void senderOrReceiver(){
        String choice = "";
        while(!choice.equals("1") && !choice.equals("2")){
            System.out.println("Do you want to:");
            System.out.println("1) Send a File      (1)");
            System.out.println("2) Receive a File   (2)");
            System.out.print("Choice: ");
            choice = scanner.nextLine().trim();
            if(!choice.equals("1") && !choice.equals("2")){
                System.out.println("Invalid choice");
            }
        }
        isSender = choice.equals("1");
    }

    private void sender() throws Exception {
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
        log.info("DataChannel Ready.");

        //stop the web socket
        log.info("Closing the Web Socket");
        signalingClient.stop();

        //start sending files
        System.out.print("Start File sending(Y/N): ");
        String choice = scanner.nextLine().trim();
        if(choice.equals("N")) return;//TODO:handle later

        startFileSending();
        //no latch as the control will return after complete transfer
        System.out.println("File transfer Done, Ending current Session");
        webRTC.shutDown();
    }

    private void receiver() throws Exception {
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

        //stop the web socket
        log.info("Closing the Web Socket");
        signalingClient.stop();

        //start receiving files
        startFileReceiving();

        //wait for the file transfer to be completed
        countDownLatch = new CountDownLatch(1);
        countDownLatch.await();

        System.out.println("File transfer Done, Ending current Session");
        webRTC.shutDown();
    }

    private void startFileSending() throws Exception {
        FileSender fileSender = new FileSender(webRTC, this);
        SenderMessageHandler senderMessageHandler = new SenderMessageHandler(fileSender);
        webRTC.setMessageHandler(senderMessageHandler);

        fileSender.start();
    }

    private void startFileReceiving() throws Exception {
        FileReceiver fileReceiver = new FileReceiver(webRTC, this);
        webRTC.setMessageHandler(fileReceiver);

        fileReceiver.start();
    }

    private void getRoomCode(){
        //get the room code
        System.out.print("Enter the Room Code: ");
        roomCode = scanner.nextLine();

        //set the roomCode
        signalingClient.setRoomCode(roomCode);

        //try to join the room with the room code
        SignalingMessage signalingMessage = new SignalingMessage(MessageType.JOIN);
        signalingClient.sendMessage(signalingMessage);

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

    @Override
    public void onError(String error) {
        if(error.equalsIgnoreCase("Room does not exists")){
            System.out.println("\nERROR: Room does not exists");
            getRoomCode();
        }else if(error.equalsIgnoreCase("Room is Full")){
            System.out.println("\nERROR: Room is Full");
            getRoomCode();
        }
    }

    @Override
    public void onRole(String message){
        //will be true if the other peer is also trying to be sender
        isOtherPeerSender = Boolean.parseBoolean(message);

        while(countDownLatch.getCount() == 0){
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        countDownLatch.countDown();
    }

    @Override
    public void onFileTransferComplete(){
//        if(countDownLatch != null)
//            countDownLatch.countDown();
        while(countDownLatch.getCount() == 0){
            System.out.println("counting");
        }
        countDownLatch.countDown();
    }
}
