package pop3;

import common.util.Config;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MailboxReader {
    public static List<String> getEmailFiles(String username) {
        List<String> files = new ArrayList<>();
        File dir = new File(Config.MAILBOX_ROOT + username.toLowerCase());
        if (dir.exists() && dir.isDirectory()) {
            File[] fileList = dir.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    if (file.getName().endsWith(".eml")) {
                        files.add(file.getName());
                    }
                }
            }
        }
        return files;
    }

    public static String readEmailContent(String username, String fileName) {
        try {
            File file = new File(Config.MAILBOX_ROOT + username.toLowerCase() + "/" + fileName);
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static boolean deleteEmail(String username, String fileName) {
        File file = new File(Config.MAILBOX_ROOT + username.toLowerCase() + "/" + fileName);
        return file.delete();
    }
}