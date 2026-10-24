package pop3;

import common.util.Config;
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Pop3ClientHandler implements Runnable {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String currentUser = null;
    private boolean isAuthenticated = false;
    private Set<Integer> markedForDeletion = new HashSet<>();
    private List<String> emailFiles = new ArrayList<>();

    public Pop3ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            out.println("+OK POP3 Server san sang");

            String commandLine;
            while ((commandLine = in.readLine()) != null) {
                String[] parts = commandLine.split(" ", 2);
                String command = parts[0].toUpperCase();

                if (!isAuthenticated && !command.equals("USER") && !command.equals("PASS") && !command.equals("QUIT")) {
                    out.println("-ERR Vui long dang nhap truoc (USER/PASS)");
                    continue;
                }

                switch (command) {
                    case "USER":
                        handleUser(parts.length > 1 ? parts[1] : "");
                        break;
                    case "PASS":
                        handlePass(parts.length > 1 ? parts[1] : "");
                        break;
                    case "STAT":
                        handleStat();
                        break;
                    case "LIST":
                        handleList();
                        break;
                    case "RETR":
                        handleRetr(parts.length > 1 ? parts[1] : "");
                        break;
                    case "DELE":
                        handleDele(parts.length > 1 ? parts[1] : "");
                        break;
                    case "QUIT":
                        handleQuit();
                        return;
                    default:
                        out.println("-ERR Lenh khong hop le");
                }
            }
        } catch (Exception e) {
            System.err.println("Loi ket noi POP3: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception e) {}
        }
    }

    private void handleUser(String username) {
        File usersFile = new File(Config.USERS_FILE);
        if (!usersFile.exists()) {
            out.println("-ERR File users.txt khong ton tai");
            return;
        }
        
        try {
            List<String> lines = Files.readAllLines(usersFile.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(":");
                if (parts.length >= 2 && parts[0].trim().equalsIgnoreCase(username)) {
                    this.currentUser = username.toLowerCase();
                    this.emailFiles = MailboxReader.getEmailFiles(currentUser);
                    out.println("+OK User hop le, nhap mat khau");
                    return;
                }
            }
            out.println("-ERR User khong ton tai");
        } catch (Exception e) {
            out.println("-ERR Loi doc file users.txt");
        }
    }

    private void handlePass(String password) {
        try {
            List<String> lines = Files.readAllLines(new File(Config.USERS_FILE).toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(":");
                if (parts.length >= 2 && parts[0].trim().equalsIgnoreCase(currentUser) && parts[1].trim().equals(password)) {
                    isAuthenticated = true;
                    out.println("+OK Dang nhap thanh cong");
                    return;
                }
            }
            out.println("-ERR Mat khau sai");
        } catch (Exception e) {
            out.println("-ERR Loi xac thuc");
        }
    }

    private void handleStat() {
        int count = 0;
        long totalSize = 0;
        for (int i = 0; i < emailFiles.size(); i++) {
            if (!markedForDeletion.contains(i + 1)) {
                count++;
                File f = new File(Config.MAILBOX_ROOT + currentUser + "/" + emailFiles.get(i));
                totalSize += f.length();
            }
        }
        out.println("+OK " + count + " " + totalSize);
    }

    private void handleList() {
        out.println("+OK Danh sach thu");
        for (int i = 0; i < emailFiles.size(); i++) {
            if (!markedForDeletion.contains(i + 1)) {
                File f = new File(Config.MAILBOX_ROOT + currentUser + "/" + emailFiles.get(i));
                out.println((i + 1) + " " + f.length());
            }
        }
        out.println(".");
    }

    private void handleRetr(String indexStr) {
        try {
            int index = Integer.parseInt(indexStr) - 1;
            if (index >= 0 && index < emailFiles.size() && !markedForDeletion.contains(index + 1)) {
                String content = MailboxReader.readEmailContent(currentUser, emailFiles.get(index));
                out.println("+OK Lay thu thanh cong");
                String[] lines = content.split("\r\n");
                for (String line : lines) {
                    if (line.startsWith(".")) {
                        out.println("." + line);
                    } else {
                        out.println(line);
                    }
                }
                out.println(".");
            } else {
                out.println("-ERR Thu khong ton tai");
            }
        } catch (Exception e) {
            out.println("-ERR Loi doc thu");
        }
    }

    private void handleDele(String indexStr) {
        try {
            int index = Integer.parseInt(indexStr);
            if (index > 0 && index <= emailFiles.size()) {
                markedForDeletion.add(index);
                out.println("+OK Thu " + index + " da duoc danh dau xoa");
            } else {
                out.println("-ERR Index khong hop le");
            }
        } catch (Exception e) {
            out.println("-ERR Loi");
        }
    }

    private void handleQuit() {
        out.println("+OK Tam biet");
        for (int index : markedForDeletion) {
            if (index > 0 && index <= emailFiles.size()) {
                MailboxReader.deleteEmail(currentUser, emailFiles.get(index - 1));
            }
        }
    }
}