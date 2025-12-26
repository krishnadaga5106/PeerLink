import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;

public class FileManager {

    public static void main(String[] args) {
        String[] arr = getFile();
        for(String s: arr){
            System.out.println(s);
        }
    }

    private static String[] getFile(){
        String paths = TinyFileDialogs.tinyfd_openFileDialog(
                "Select Files",
                "",
                null,
                "Select a File",
                true
        );
        if(paths == null) return null;

        paths = paths.replace("\\", "\\\\");
        return paths.split("\\|");
    }

}
