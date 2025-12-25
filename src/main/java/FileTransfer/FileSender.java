package FileTransfer;

import Interfaces.MessageHandler;
import WebRTC.P2PWebRTC;
import lombok.RequiredArgsConstructor;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

@RequiredArgsConstructor
public class FileSender {
    private final P2PWebRTC webRTC;
    private CountDownLatch latch;

    private String ackMsg;

    public void start() throws Exception {
        //get files / folders
        //get ACK from the receiver
        //For each file, break into chunk and start transfer
        //update the main thread regarding the progress

        //Step-1
        String[] filePaths = getFiles();

        //calculate info about the file(s)
        ArrayList<File> files = new ArrayList<File>();
        for(String path : filePaths){
            files.add(new File(path));
        }

        //FOR NOW JUST TO MAKE IT SIMPLER, GET ACK REGARDING THE WHOLE TRANSFER CONTENT, not ACK for individual file
        //TODO: get ACK for individual file
        long sizeInBytes = 0L;
        StringBuilder fileNames = new StringBuilder();
        for(File file : files){
            sizeInBytes += file.length();
            fileNames.append(file.getName()).append("|");
        }
        //convert to KB
        double sizeInKB = (double) sizeInBytes / 1024;
        String META_DATA = "REQ::" + fileNames.toString() + "::" + sizeInKB;
        ByteBuffer buffer = ByteBuffer.wrap(META_DATA.getBytes());

        //send transfer Req
        webRTC.send(buffer, false);
        System.out.println("Waiting for ACK...");

        //wait for ACK
        latch = new CountDownLatch(1);
        latch.await();

        System.out.println(ackMsg);

    }

    private String[] getFiles(){
        String paths = TinyFileDialogs.tinyfd_openFileDialog(
                "Select Files",
                "",
                null,
                "Select Files",
                true
        );
        if(paths == null) return null;

        paths = paths.replace("\\", "\\\\");
        return paths.split("\\|");
    }

    public void onACK(String ackMsg){
        latch.countDown();
        this.ackMsg = ackMsg;
    }

}
