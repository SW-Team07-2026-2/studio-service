package com.indie.studio.domain.auth.service;

import com.indie.studio.global.config.StudioProperties;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** Studio -> SMTP -> 관리자 계정에 등록된 주소로 로그인 OTP를 개별 발송합니다. */
@Component
public class OtpMailer {

    private final MailSender mailSender;
    private final String from;

    public OtpMailer(MailSender mailSender, StudioProperties props) {
        this.mailSender = mailSender;
        this.from = props.login().mailFrom();
    }

    /**
     * 못 보내면 MailException - 부르는 쪽(AuthService.login)이 2단계 대기를 지웁니다
     *
     * @param loginId 관리자 로그인 ID
     * @param to 공연 조회 종료일
     * @param code 약관 또는 인증 코드
     * @param ttl 인증 코드 유효 시간
     */
    public void send(String loginId, List<String> to, String code, Duration ttl) {
        String text = """
                indig 관리자 화면 로그인 인증 코드입니다.

                인증 코드: %s
                계정: %s

                %d분 안에 한 번만 쓸 수 있습니다.
                직접 로그인하지 않았다면 누군가 이 계정의 비밀번호를 알고 있다는 뜻입니다. 비밀번호를 바꾸고 마스터에게 알려 주세요.

                이 메일은 발신 전용입니다.
                """.formatted(code, loginId, ttl.toMinutes());
        mailSender.send(to.stream().map(address -> {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(address);
            message.setSubject("[indig] 관리자 로그인 인증 코드");
            message.setText(text);
            return message;
        }).toArray(SimpleMailMessage[]::new));
    }
}
