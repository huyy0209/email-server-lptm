# Email Server nội bộ (POP3 / SMTP) – Lập trình mạng máy tính

Đồ án cuối kỳ môn **Lập trình mạng máy tính** – Đề tài I.1: Hệ thống Email Server nội bộ hỗ trợ POP3 và SMTP Client.
Triển khai bằng **Java** (Socket, đa luồng).

## Thành phần và tiến độ

| Module | Mô tả | Người phụ trách | Trạng thái |
|---|---|---|---|
| SMTP Server | Nhận thư từ client, xác thực, lưu vào mailbox | Người 1 (Huy) | Hoàn thành, đã test |
| POP3 Server | Cho client tải thư từ mailbox | Người 2 (Đạt) | Chạy được, đang sửa lỗi (xem mục "Việc cần làm") |
| Client GUI | Giao diện gửi / nhận thư | Người 3 (Quân) | Chưa có trên repo |

## Cấu trúc thư mục

```
email-server-lptm/
├── src/
│   ├── common/
│   │   ├── model/Email.java            # Model thư (hiện chưa dùng)
│   │   └── util/Config.java            # Hằng số dùng chung: cổng, thư mục mailbox, file users
│   ├── smtp/
│   │   ├── SmtpServer.java             # Mở cổng 2525, nhận kết nối (thread pool)
│   │   ├── SmtpClientHandler.java      # Xử lý lệnh SMTP của một client
│   │   ├── UserValidator.java          # Đọc users.txt, kiểm tra user và mật khẩu
│   │   ├── MailboxWriter.java          # Ghi thư ra file .eml
│   │   └── SmtpLogger.java             # Ghi log ra console và logs/smtp.log
│   └── pop3/
│       ├── Pop3Server.java             # Mở cổng 1100, nhận kết nối
│       ├── Pop3ClientHandler.java      # Xử lý lệnh POP3 của một client
│       └── MailboxReader.java          # Đọc, xóa file .eml trong mailbox
├── users.example.txt                   # File user mẫu
├── mailbox/                            # (tự sinh) thư của từng user
├── logs/                               # (tự sinh) file log
├── out/                                # (tự sinh) file .class sau khi biên dịch
└── README.md
```

`users.txt`, `mailbox/`, `logs/`, `out/` nằm trong `.gitignore`, không commit lên repo.

## Cổng dịch vụ

| Dịch vụ | Cổng |
|---|---|
| SMTP Server | 2525 |
| POP3 Server | 1100 |

## Yêu cầu

- JDK 11 trở lên (kiểm tra: `java -version`, `javac -version`)

## Cấu hình user

Tạo `users.txt` từ file mẫu:

```powershell
Copy-Item users.example.txt users.txt
```

Mỗi dòng một user, định dạng `tên_user:mật_khẩu`. Dòng bắt đầu bằng `#` là chú thích.

```
huy:123456
an:abc123
binh:matkhau
```

> Dùng mật khẩu thử nghiệm, không dùng mật khẩu thật. Không commit `users.txt`.

## Biên dịch và chạy

Chạy tất cả lệnh từ **thư mục gốc của repo**, để SMTP và POP3 dùng chung `mailbox/` và `users.txt`.

```powershell
# Biên dịch toàn bộ
javac -d out src/common/model/*.java src/common/util/*.java src/smtp/*.java src/pop3/*.java

# Terminal 1: SMTP Server (mặc định: cổng 2525, thư mục mailbox, file users.txt)
java -cp out smtp.SmtpServer
# Hoặc chỉ định tham số: [port] [mailboxDir] [usersFile]
java -cp out smtp.SmtpServer 2525 mailbox users.txt

# Terminal 2: POP3 Server (cổng 1100, cấu hình trong Config.java)
java -cp out pop3.Pop3Server
```

## Lệnh SMTP được hỗ trợ

`HELO`, `EHLO`, `AUTH PLAIN`, `AUTH LOGIN`, `MAIL FROM`, `RCPT TO`, `DATA`, `RSET`, `NOOP`, `VRFY`, `QUIT`

- Phải đăng nhập (`AUTH`) trước khi gửi thư.
- Địa chỉ `MAIL FROM` phải trùng với user đã đăng nhập.
- Người nhận không có trong `users.txt` bị từ chối với mã `550`.
- Đăng nhập sai 3 lần, server ngắt kết nối.
- Mật khẩu không bao giờ được ghi vào log (`AUTH ***`).

Hai tùy chọn xác thực có thể bật/tắt bằng hằng `REQUIRE_AUTH` và `ENFORCE_SENDER_MATCH` trong `SmtpClientHandler.java`.

> SMTP Server lưu nguyên phần `DATA` client gửi lên, chỉ thêm `Return-Path` và `Received`. Client phải tự ghi các header `From:`, `To:`, `Subject:`, `Date:` ở đầu nội dung, rồi một dòng trống, rồi tới nội dung thư.

## Lệnh POP3 được hỗ trợ

`USER`, `PASS`, `STAT`, `LIST`, `RETR`, `DELE`, `QUIT`

- Phải đăng nhập (`USER` rồi `PASS`) trước khi dùng các lệnh còn lại.
- `DELE` chỉ đánh dấu xóa, thư bị xóa thật khi `QUIT`.

## Cấu trúc mailbox

Mỗi người nhận có một thư mục, mỗi thư là một file `.eml`:

```
mailbox/
└── <tên_user>/
    ├── 20261018130000123-a1b2c3d4.eml
    └── ...
```

Tên thư mục là phần trước dấu `@` của địa chỉ, viết thường. POP3 Server đọc đúng cấu trúc này.

## Kiểm thử nhanh

Bật cả hai server (xem mục "Biên dịch và chạy"), mở terminal thứ ba.

**Cách 1: PowerShell** (không cần cài `telnet`). Dán hàm trợ giúp một lần:

```powershell
function Send-Lines($port, $lines) {
  $c = New-Object Net.Sockets.TcpClient('localhost', $port)
  $s = $c.GetStream(); $r = New-Object IO.StreamReader($s)
  $w = New-Object IO.StreamWriter($s); $w.AutoFlush = $true; $w.NewLine = "`r`n"
  Start-Sleep -m 300; while ($s.DataAvailable) { $r.ReadLine() }
  foreach ($l in $lines) { Write-Host ">> $l"; $w.WriteLine($l); Start-Sleep -m 300; while ($s.DataAvailable) { $r.ReadLine() } }
  $c.Close()
}
```

Gửi thư từ `huy` sang `an` bằng SMTP:

```powershell
Send-Lines 2525 @("EHLO test","AUTH PLAIN AGh1eQAxMjM0NTY=","MAIL FROM:<huy@mail.local>","RCPT TO:<an@mail.local>","DATA","From: huy@mail.local","To: an@mail.local","Subject: test","","Xin chao","hello An",".","QUIT")
```

Đọc thư của `an` bằng POP3:

```powershell
Send-Lines 1100 @("USER an","PASS abc123","STAT","LIST","RETR 1","QUIT")
```

(Hàm in kết quả trễ một nhịp so với lệnh tương ứng, đây không phải lỗi server.)

**Cách 2: `telnet` hoặc `nc`.** Kết nối `telnet localhost 2525`, nhập lần lượt:

```
EHLO test
AUTH PLAIN AGh1eQAxMjM0NTY=
MAIL FROM:<huy@mail.local>
RCPT TO:<an@mail.local>
DATA
Subject: test

Xin chao
.
QUIT
```

`AGh1eQAxMjM0NTY=` là base64 của `\0huy\0123456` (user `huy`, mật khẩu `123456`). Nếu `users.txt` dùng mật khẩu khác, tạo lại chuỗi này:

```powershell
[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("`0huy`0123456"))
```

Kết quả mong đợi: file `.eml` mới trong `mailbox/an/`, các dòng log trong `logs/smtp.log`, và POP3 `RETR 1` trả đúng nội dung thư.

## Việc cần làm

POP3 Server (Đạt):
- [ ] **Sửa lỗ hổng:** sau khi đăng nhập, lệnh `USER <người khác>` làm đổi hộp thư mà không cần mật khẩu (`isAuthenticated` không được đặt lại trong `handleUser`).
- [ ] Sắp xếp danh sách file trong `MailboxReader.getEmailFiles` để số thứ tự thư ổn định.
- [ ] Thêm lệnh `NOOP`, `RSET`, `CAPA`.
- [ ] Gửi dòng kết thúc bằng `\r\n` thay vì `println`.
- [ ] Ghi log ra file, dùng thread pool.

Client GUI (Quân):
- [ ] Tạo nhánh `feature/client-gui`, gửi thư qua SMTP (có `AUTH`), nhận thư qua POP3.

Chung:
- [ ] Test ghép toàn hệ thống, quay video demo, viết báo cáo.

## Quy trình làm việc với Git

- Không push trực tiếp lên `main`. Mỗi người làm trên nhánh riêng:
  - `feature/smtp-server`
  - `feature/pop3-server`
  - `feature/client-gui`
- Commit theo quy ước: `feat:`, `fix:`, `docs:`, `chore:`, `refactor:`.
- Mở Pull Request, có ít nhất một bạn trong nhóm review rồi mới merge. Không tự merge PR của mình.
- Trước khi bắt đầu việc mới: `git checkout main` rồi `git pull origin main`.
- Chạy từng lệnh git riêng lẻ. Lệnh `git pull` có thể mở trình soạn thảo (vim); thoát bằng `Esc`, gõ `:q!` rồi `Enter` (hoặc dùng `git pull --no-edit`).