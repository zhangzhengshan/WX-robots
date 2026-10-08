package com.mumu.bot;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

/**
 * 临时冒烟测试（验证 jakarta.activation 依赖修复后可删除）：
 * 构造 MimeMessage 并 writeTo，强制触发 DataHandler / MailcapCommandMap
 * 类加载——之前 NoClassDefFoundError: jakarta/activation/DataHandler 就炸在这条路径上。
 */
public final class MailSmokeCheck {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        p.put("mail.smtp.host", "smtp.163.com");
        Session session = Session.getInstance(p);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress("test@test.com"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("test@test.com"));
        msg.setSubject("smoke");
        msg.setText("hello", "UTF-8");
        msg.saveChanges();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        msg.writeTo(bos);
        System.out.println("MAIL SMOKE OK, serialized " + bos.size() + " bytes");
    }
}
