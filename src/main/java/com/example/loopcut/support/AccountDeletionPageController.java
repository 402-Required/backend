package com.example.loopcut.support;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Google Play Console에 등록하는 "웹 계정 삭제 요청 페이지". 인증 없이 열려야 하고 /api/v1 접두사도 붙지 않는다.
 * (@RestController가 아니라 @Controller라서 WebConfig의 접두사/인터셉터 대상이 아님)
 *
 * TODO: 문의 이메일 주소는 실제 운영 주소로 바꿔 주세요.
 */
@Controller
public class AccountDeletionPageController {

    private static final String PAGE = """
            <!doctype html>
            <html lang="ko">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>LoopCut 계정 삭제 안내</title>
              <style>
                body { font-family: -apple-system, "Noto Sans KR", sans-serif; max-width: 640px; margin: 40px auto; padding: 0 16px; line-height: 1.6; color: #222; }
                h1 { font-size: 22px; } h2 { font-size: 17px; margin-top: 28px; }
              </style>
            </head>
            <body>
              <h1>LoopCut(루프컷) 계정 삭제 안내</h1>
              <h2>앱에서 직접 삭제하기</h2>
              <p>앱의 <b>마이 &gt; 회원 탈퇴</b> 메뉴에서 바로 계정을 삭제할 수 있어요.</p>
              <h2>이메일로 삭제 요청하기</h2>
              <p>앱을 사용할 수 없는 경우, 가입한 이메일 주소로 <b>support@loopcut.app</b>에 "계정 삭제 요청"이라고 보내 주세요. 본인 확인 후 처리해 드려요.</p>
              <h2>삭제되는 데이터</h2>
              <p>계정 정보(이메일, 이름, 로그인 정보), 등록한 구독과 결제 내역, 알림 설정과 알림함, 검색 기록, 문의 내역이 모두 삭제되며 복구할 수 없어요.</p>
              <h2>보관되는 데이터</h2>
              <p>삭제 요청 후 별도로 보관하는 개인정보는 없어요.</p>
            </body>
            </html>
            """;

    @GetMapping(value = "/account-deletion", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    @ResponseBody
    public String page() {
        return PAGE;
    }
}
