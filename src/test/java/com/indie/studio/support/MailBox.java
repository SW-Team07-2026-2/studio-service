package com.indie.studio.support;

import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 가짜 메일 서버 - 보낸 메일을 모아 두고, 받는 주소로 인증 코드를 꺼냅니다. failNext() 뒤 한 번은 SMTP 장애처럼 실패합니다 */
public class MailBox implements MailSender {

    private static final Pattern CODE = Pattern.compile("인증 코드: (\\d{6})");

    private final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failNext;

    @Override
    public void send(SimpleMailMessage message) {
        send(new SimpleMailMessage[] {message});
    }

    @Override
    public void send(SimpleMailMessage... messages) {
        if (failNext) {
            failNext = false;
            throw new MailSendException("SMTP 연결 실패 (테스트)");
        }
        sent.addAll(List.of(messages));
    }

    public void failNext() {
        failNext = true;
    }

    public int count() {
        return sent.size();
    }

    /** 이 주소로 간 메일 전부 (보낸 순서) */
    public List<SimpleMailMessage> to(String address) {
        return sent.stream().filter(m -> List.of(m.getTo()).contains(address)).toList();
    }

    /** 이 주소로 간 마지막 메일의 인증 코드 */
    public String lastCode(String address) {
        List<SimpleMailMessage> mine = to(address);
        if (mine.isEmpty()) throw new AssertionError("메일이 오지 않았습니다: " + address);
        Matcher m = CODE.matcher(mine.get(mine.size() - 1).getText());
        if (!m.find()) throw new AssertionError("메일에 인증 코드가 없습니다: " + address);
        return m.group(1);
    }
}
