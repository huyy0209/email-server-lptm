package common.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Email {
    private String from;
    private String to;
    private String subject;
    private String body;
    private LocalDateTime date;
    private String messageId;

    public Email() {
        this.date = LocalDateTime.now();
        this.messageId = System.currentTimeMillis() + "-" + (int)(Math.random() * 100000);
    }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    
    public LocalDateTime getDate() { return date; }
    public void setDate(LocalDateTime date) { this.date = date; }
    
    public String getMessageId() { return messageId; }

    public String toEmlFormat() {
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(from).append("\r\n");
        sb.append("To: ").append(to).append("\r\n");
        sb.append("Subject: ").append(subject).append("\r\n");
        sb.append("Date: ").append(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\r\n");
        sb.append("Message-ID: ").append(messageId).append("\r\n");
        sb.append("\r\n");
        sb.append(body);
        return sb.toString();
    }

    public static Email fromEmlFormat(String emlContent) {
        Email email = new Email();
        String[] lines = emlContent.split("\r\n");
        boolean bodyStarted = false;
        StringBuilder bodyBuilder = new StringBuilder();
        
        for (String line : lines) {
            if (bodyStarted) {
                bodyBuilder.append(line).append("\r\n");
            } else if (line.isEmpty()) {
                bodyStarted = true;
            } else if (line.startsWith("From: ")) {
                email.setFrom(line.substring(6));
            } else if (line.startsWith("To: ")) {
                email.setTo(line.substring(4));
            } else if (line.startsWith("Subject: ")) {
                email.setSubject(line.substring(9));
            }
        }
        
        if (bodyStarted) {
            email.setBody(bodyBuilder.toString().trim());
        }
        
        return email;
    }
}