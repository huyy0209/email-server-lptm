package smtp;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Xử lý MỘT kết nối SMTP từ client (chạy trong một thread riêng).
 * Hỗ trợ: HELO, EHLO, AUTH (PLAIN, LOGIN), MAIL FROM, RCPT TO, DATA, RSET, NOOP, VRFY, QUIT.
 */
public class SmtpClientHandler implements Runnable {

    private static final int TIMEOUT_MS = 5 * 60 * 1000; // 5 phút không hoạt động thì ngắt
    private static final int MAX_AUTH_FAILS = 3;          // sai quá 3 lần thì ngắt kết nối

    /** Bắt buộc đăng nhập (AUTH) trước khi gửi thư. */
    private static final boolean REQUIRE_AUTH = true;
    /** Địa chỉ MAIL FROM phải trùng với user đã đăng nhập (chống giả mạo người gửi). */
    private static final boolean ENFORCE_SENDER_MATCH = true;

    private final Socket socket;
    private final UserValidator validator;
    private final MailboxWriter writer;

    private BufferedReader in;
    private BufferedWriter out;

    // Trạng thái phiên (session) hiện tại
    private boolean greeted = false;          // đã HELO/EHLO chưa
    private String authUser = null;           // user đã đăng nhập (null = chưa)
    private int authFails = 0;                // số lần đăng nhập sai
    private String mailFrom = null;           // MAIL FROM
    private final List<String> recipients = new ArrayList<>(); // các RCPT TO hợp lệ

    public SmtpClientHandler(Socket socket, UserValidator validator, MailboxWriter writer) {
        this.socket = socket;
        this.validator = validator;
        this.writer = writer;
    }

    @Override
    public void run() {
        String client = socket.getRemoteSocketAddress().toString();
        SmtpLogger.info("[SMTP] Kết nối từ " + client);
        try {
            socket.setSoTimeout(TIMEOUT_MS);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            reply("220 localhost SMTP Server LPTM ready");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                // Không ghi dữ liệu đăng nhập vào log
                if (line.toUpperCase().startsWith("AUTH")) {
                    SmtpLogger.info("[SMTP] C(" + client + "): AUTH ***");
                } else {
                    SmtpLogger.info("[SMTP] C(" + client + "): " + line);
                }
                if (!handleCommand(line)) break; // QUIT hoặc bị ngắt
            }
        } catch (SocketTimeoutException e) {
            try { reply("421 Timeout, closing connection"); } catch (IOException ignored) {}
        } catch (IOException e) {
            SmtpLogger.error("[SMTP] Lỗi với " + client + ": " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            SmtpLogger.info("[SMTP] Đã đóng kết nối " + client);
        }
    }

    /** Xử lý một lệnh. Trả về false nếu cần đóng kết nối. */
    private boolean handleCommand(String line) throws IOException {
        if (line.isEmpty()) {
            reply("500 Syntax error, command unrecognized");
            return true;
        }
        String upper = line.toUpperCase();
        String cmd = upper.split("\\s+")[0];

        switch (cmd) {
            case "HELO":
                greeted = true;
                resetTransaction();
                reply("250 localhost Hello");
                break;

            case "EHLO":
                greeted = true;
                resetTransaction();
                out.write("250-localhost Hello\r\n");
                out.write("250-AUTH PLAIN LOGIN\r\n");
                reply("250 8BITMIME");
                break;

            case "AUTH":
                if (!handleAuth(line)) return false;
                break;

            case "MAIL":
                handleMailFrom(line, upper);
                break;

            case "RCPT":
                handleRcptTo(line, upper);
                break;

            case "DATA":
                handleData();
                break;

            case "RSET":
                resetTransaction();
                reply("250 OK");
                break;

            case "NOOP":
                reply("250 OK");
                break;

            case "VRFY": {
                String arg = line.length() > 5 ? extractAddress(line.substring(5)) : "";
                if (validator.isValidUser(arg)) reply("250 " + arg);
                else reply("550 User not found");
                break;
            }

            case "QUIT":
                reply("221 Bye");
                return false;

            default:
                reply("500 Syntax error, command unrecognized");
        }
        return true;
    }

    // ---------- AUTH ----------
    /** Trả về false nếu cần đóng kết nối (sai mật khẩu quá nhiều lần). */
    private boolean handleAuth(String line) throws IOException {
        if (!greeted) { reply("503 Bad sequence of commands (send HELO/EHLO first)"); return true; }
        if (authUser != null) { reply("503 Already authenticated"); return true; }
        if (mailFrom != null) { reply("503 AUTH not permitted during a mail transaction"); return true; }

        String[] parts = line.split("\\s+");
        if (parts.length < 2) { reply("501 Syntax: AUTH mechanism"); return true; }
        String mech = parts[1].toUpperCase();

        String user;
        String pass;

        if (mech.equals("PLAIN")) {
            // AUTH PLAIN <base64>  hoặc  AUTH PLAIN rồi gửi base64 ở dòng sau
            String b64 = parts.length > 2 ? parts[2] : null;
            if (b64 == null) {
                reply("334 ");
                b64 = readAuthLine();
            }
            if (b64.equals("*")) { reply("501 Authentication cancelled"); return true; }
            String decoded = decode(b64);
            if (decoded == null) { reply("501 Invalid base64 data"); return true; }
            // Định dạng: authzid \0 username \0 password
            String[] f = decoded.split("\0", -1);
            if (f.length != 3) { reply("501 Malformed PLAIN data"); return true; }
            user = f[1];
            pass = f[2];

        } else if (mech.equals("LOGIN")) {
            // Có thể kèm sẵn username: AUTH LOGIN <base64 username>
            String uB64 = parts.length > 2 ? parts[2] : null;
            if (uB64 == null) {
                reply("334 " + encode("Username:"));
                uB64 = readAuthLine();
            }
            if (uB64.equals("*")) { reply("501 Authentication cancelled"); return true; }
            reply("334 " + encode("Password:"));
            String pB64 = readAuthLine();
            if (pB64.equals("*")) { reply("501 Authentication cancelled"); return true; }
            user = decode(uB64);
            pass = decode(pB64);
            if (user == null || pass == null) { reply("501 Invalid base64 data"); return true; }

        } else {
            reply("504 Unrecognized authentication mechanism");
            return true;
        }

        if (validator.authenticate(user, pass)) {
            authUser = UserValidator.localPart(user);
            SmtpLogger.info("[SMTP] Đăng nhập thành công: " + authUser);
            reply("235 2.7.0 Authentication successful");
            return true;
        }

        authFails++;
        SmtpLogger.error("[SMTP] Đăng nhập thất bại (user=" + user + ", lần " + authFails + ")");
        if (authFails >= MAX_AUTH_FAILS) {
            reply("421 Too many failed attempts, closing connection");
            return false;
        }
        reply("535 5.7.8 Authentication credentials invalid");
        return true;
    }

    /** Đọc một dòng trong quá trình AUTH (không ghi log vì chứa mật khẩu). */
    private String readAuthLine() throws IOException {
        String s = in.readLine();
        if (s == null) throw new IOException("Client ngắt kết nối khi đang AUTH");
        return s.trim();
    }

    private static String encode(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Giải mã base64, trả về null nếu sai định dạng. */
    private static String decode(String s) {
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------- MAIL FROM ----------
    private void handleMailFrom(String line, String upper) throws IOException {
        if (!greeted) { reply("503 Bad sequence of commands (send HELO/EHLO first)"); return; }
        if (REQUIRE_AUTH && authUser == null) { reply("530 5.7.0 Authentication required"); return; }
        if (!upper.startsWith("MAIL FROM:")) { reply("501 Syntax: MAIL FROM:<address>"); return; }
        if (mailFrom != null) { reply("503 Sender already specified"); return; }

        String addr = extractAddress(line.substring("MAIL FROM:".length()));
        // Cho phép <> (thư bounce) nhưng nếu có địa chỉ thì phải đúng cú pháp
        if (!addr.isEmpty() && !UserValidator.isWellFormed(addr)) {
            reply("501 Invalid sender address");
            return;
        }
        if (REQUIRE_AUTH && ENFORCE_SENDER_MATCH
                && !UserValidator.localPart(addr).equals(authUser)) {
            reply("553 5.7.1 Sender address does not match authenticated user");
            return;
        }
        mailFrom = addr;
        reply("250 OK");
    }

    // ---------- RCPT TO ----------
    private void handleRcptTo(String line, String upper) throws IOException {
        if (mailFrom == null) { reply("503 Bad sequence of commands (need MAIL FROM first)"); return; }
        if (!upper.startsWith("RCPT TO:")) { reply("501 Syntax: RCPT TO:<address>"); return; }

        String addr = extractAddress(line.substring("RCPT TO:".length()));
        if (!UserValidator.isWellFormed(addr) || addr.isEmpty()) {
            reply("501 Invalid recipient address");
            return;
        }
        if (!validator.isValidUser(addr)) {
            reply("550 No such user here");
            return;
        }
        if (!recipients.contains(addr)) recipients.add(addr);
        reply("250 OK");
    }

    // ---------- DATA ----------
    private void handleData() throws IOException {
        if (mailFrom == null || recipients.isEmpty()) {
            reply("503 Bad sequence of commands (need MAIL FROM and RCPT TO first)");
            return;
        }
        reply("354 End data with <CR><LF>.<CR><LF>");

        StringBuilder data = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals(".")) break;                    // kết thúc nội dung thư
            if (line.startsWith("..")) line = line.substring(1); // bỏ dot-stuffing
            data.append(line).append("\r\n");
        }
        if (line == null) throw new IOException("Client ngắt kết nối khi đang gửi DATA");

        // Ghi thư cho từng người nhận
        try {
            for (String rcpt : recipients) {
                writer.save(rcpt, mailFrom, data.toString());
                SmtpLogger.info("[SMTP] Đã lưu thư: " + mailFrom + " -> " + rcpt);
            }
            reply("250 OK: message accepted for delivery");
        } catch (IOException e) {
            SmtpLogger.error("[SMTP] Lỗi ghi thư: " + e.getMessage());
            reply("451 Requested action aborted: local error in processing");
        }
        resetTransaction();
    }

    // ---------- Tiện ích ----------
    /** Xóa trạng thái giao dịch thư (giữ nguyên trạng thái đăng nhập). */
    private void resetTransaction() {
        mailFrom = null;
        recipients.clear();
    }

    /** Lấy địa chỉ trong "<abc@x.com>" hoặc "abc@x.com" (bỏ tham số phía sau như SIZE=...). */
    private String extractAddress(String s) {
        s = s.trim();
        int lt = s.indexOf('<');
        int gt = s.indexOf('>');
        if (lt >= 0 && gt > lt) return s.substring(lt + 1, gt).trim();
        return s.split("\\s+")[0].trim();
    }

    /** Gửi một dòng phản hồi (kết thúc bằng CRLF) và flush. */
    private void reply(String msg) throws IOException {
        SmtpLogger.info("[SMTP] S: " + msg);
        out.write(msg + "\r\n");
        out.flush();
    }
}