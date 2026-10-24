package smtp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Quản lý user từ users.txt: kiểm tra người nhận tồn tại (RCPT TO) và xác thực đăng nhập (AUTH).
 *
 * Định dạng users.txt (mỗi dòng một user, dòng bắt đầu bằng # là chú thích):
 *   huy:123456
 *   an@mail.local:abc
 *   binh            (không có mật khẩu -> nhận thư được nhưng không đăng nhập được)
 * Tên user là phần trước dấu ':' đầu tiên; phần sau là mật khẩu (có thể chứa ':').
 */
public class UserValidator {

    private final Path usersFile;
    private volatile Map<String, String> users = new HashMap<>(); // tên user -> mật khẩu

    public UserValidator(Path usersFile) {
        this.usersFile = usersFile;
        reload();
    }

    /** Đọc lại users.txt. */
    public synchronized void reload() {
        Map<String, String> loaded = new HashMap<>();
        try {
            for (String line : Files.readAllLines(usersFile, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int colon = line.indexOf(':');
                String name = (colon >= 0 ? line.substring(0, colon) : line).trim().toLowerCase();
                String pass = colon >= 0 ? line.substring(colon + 1).trim() : "";
                if (!name.isEmpty()) loaded.put(name, pass);
            }
        } catch (IOException e) {
            SmtpLogger.error("[UserValidator] Không đọc được " + usersFile + ": " + e.getMessage());
        }
        users = loaded;
        SmtpLogger.info("[UserValidator] Đã nạp " + users.size() + " user.");
    }

    /** Địa chỉ hợp lệ nếu có đủ "user@domain" hoặc chỉ phần "user" trong users.txt. */
    public boolean isValidUser(String address) {
        return findKey(address) != null;
    }

    /**
     * Xác thực đăng nhập. username có thể là "huy" hoặc "huy@domain".
     * @return true nếu user tồn tại, có mật khẩu và mật khẩu khớp.
     */
    public boolean authenticate(String username, String password) {
        String key = findKey(username);
        if (key == null || password == null) return false;
        String stored = users.get(key);
        if (stored == null || stored.isEmpty()) return false;
        // So sánh theo thời gian cố định để tránh timing attack
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
    }

    private String findKey(String address) {
        if (address == null) return null;
        String addr = address.trim().toLowerCase();
        if (users.containsKey(addr)) return addr;
        String local = localPart(addr);
        return users.containsKey(local) ? local : null;
    }

    /** Lấy phần trước dấu @ (tên mailbox). */
    public static String localPart(String address) {
        String a = address.trim().toLowerCase();
        int at = a.indexOf('@');
        return at >= 0 ? a.substring(0, at) : a;
    }

    /** Kiểm tra cú pháp địa chỉ email đơn giản. */
    public static boolean isWellFormed(String address) {
        return address != null && address.matches("^[A-Za-z0-9._%+-]+(@[A-Za-z0-9.-]+)?$");
    }
}