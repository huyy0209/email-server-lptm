package smtp;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SMTP Server: lắng nghe cổng 2525, mỗi client được xử lý bởi một thread trong pool.
 * Log ghi ra console và file logs/smtp.log.
 *
 * Chạy: java smtp.SmtpServer [port] [mailboxDir] [usersFile]
 * Mặc định: 2525  mailbox  users.txt
 */
public class SmtpServer {

    public static final int DEFAULT_PORT = 2525;

    private final int port;
    private final UserValidator validator;
    private final MailboxWriter writer;
    private final ExecutorService pool = Executors.newFixedThreadPool(20);
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    public SmtpServer(int port, String mailboxDir, String usersFile) {
        this.port = port;
        this.validator = new UserValidator(Paths.get(usersFile));
        this.writer = new MailboxWriter(mailboxDir);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        SmtpLogger.info("[SMTP] Server đang chạy tại cổng " + port);

        while (running) {
            try {
                Socket client = serverSocket.accept();
                pool.execute(new SmtpClientHandler(client, validator, writer));
            } catch (IOException e) {
                if (running) SmtpLogger.error("[SMTP] Lỗi accept: " + e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        pool.shutdownNow();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        SmtpLogger.info("[SMTP] Server đã dừng.");
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String mailboxDir = args.length > 1 ? args[1] : "mailbox";
        String usersFile = args.length > 2 ? args[2] : "users.txt";

        SmtpServer server = new SmtpServer(port, mailboxDir, usersFile);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try {
            server.start();
        } catch (IOException e) {
            SmtpLogger.error("[SMTP] Không thể khởi động server: " + e.getMessage());
        }
    }
}