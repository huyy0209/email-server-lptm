package pop3;

import common.util.Config;
import java.net.ServerSocket;
import java.net.Socket;

public class Pop3Server {
    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(Config.POP3_PORT)) {
            System.out.println("POP3 Server dang chay tren cong " + Config.POP3_PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client ket noi: " + clientSocket.getInetAddress());
                new Thread(new Pop3ClientHandler(clientSocket)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}