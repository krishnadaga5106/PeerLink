import Core.Controller;
import Core.MessageHandler;
import Signaling.SignalingClient;
import WebRTC.P2PWebRTC;
import lombok.extern.slf4j.Slf4j;
import java.util.Scanner;


@Slf4j
public class MainApplication {

    private static Controller controller;
    private static P2PWebRTC webRTC;
    private static SignalingClient signalingClient;

    public static void main(String[] args) throws Exception {
        MainApplication app = new MainApplication();
        app.ini();
        app.run();
    }

    public void run() throws Exception {
        controller.run();

        //stop the application
        signalingClient.stop();
        webRTC.shutDown();
    }

    private void ini() throws Exception {
        Scanner sc = new Scanner(System.in);
        System.out.print("[SYSTEM]: Enter username: ");
        String username = sc.nextLine();


        controller = new Controller();
        MessageHandler messageHandler = new MessageHandler(controller);
        controller.setMessageHandler(messageHandler);

        webRTC = new P2PWebRTC(username, controller, messageHandler);
        signalingClient = new SignalingClient(controller, webRTC);


        controller.ini(webRTC, signalingClient, username, sc);

        webRTC.setSignalingClient(signalingClient);
        webRTC.ini();


        signalingClient.connect();
        signalingClient.setUsername(username);
    }

}
