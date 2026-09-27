package com.jongbeom.server.domain.notification.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jongbeom.server.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 알림 계획 API 통합 테스트. 기본 설정(취침 23:00, 기준 75mg, 반감기 5h) 기준 고정값:
 * <ul>
 *   <li>기록 없음: 마감 = 23:00 − hoursToDecay(75→50, 5h)=2h55m29.325s = 20:04:30.675 KST(11:04:30.675Z),
 *       섭취 마감 알림은 30분 전 10:34:30.675Z</li>
 *   <li>400mg @ 13:00 KST: 23:00 취침 시 2 반감기 = 100mg ≥ 50 → 마감 없음(ALREADY_EXCEEDED),
 *       취침 잔량 예고는 60분 전 22:00 KST = 13:00:00Z</li>
 *   <li>100mg @ 09:00 KST: 취침 시 14.36mg &lt; 50 → 잔량 예고 없음, 마감 17:37:59.848 KST → 알림 08:07:59.848Z</li>
 *   <li>기록 리마인더: 과거 5일(05-27~31) 09:00 KST → 오프셋 4h 중앙값 → 평소 09:00, 알림 11:00 KST = 02:00Z.
 *       오늘 07:00 KST 100mg 이 있으면 리마인더 없음, 취침 시 10.88mg → 마감 18:18:17.035 KST → 알림 08:48:17.035Z</li>
 *   <li>카페 근처 금지 구간: 끝은 하루 경계 = 06-02 05:00 KST(2026-06-01T20:00:00Z). 기록 없는 다음 날 기본 구간은
 *       06-02 20:04:30.675 KST(11:04:30.675Z) ~ 06-03 05:00 KST(06-02T20:00:00Z)</li>
 *   <li>450mg @ 05:30 KST: 취침 시 450·2^-3.5 = 39.77mg &lt; 50 → 마감은 있으나(이미 지남) 하루 400mg 초과 → 지금부터</li>
 * </ul>
 */
class NotificationControllerIT extends AbstractIntegrationTest {

    private static final String NOW_14_KST = "2026-06-01T14:00:00+09:00";
    private static final String NOW_08_KST = "2026-06-01T08:00:00+09:00";
    private static final String[] FIVE_DAYS = {"2026-05-27", "2026-05-28", "2026-05-29", "2026-05-30", "2026-05-31"};
    private static final String[] FOUR_DAYS = {"2026-05-28", "2026-05-29", "2026-05-30", "2026-05-31"};

    private ResultActions plan(String accessToken, String now) throws Exception {
        return mockMvc.perform(get("/api/notifications/plan").header("Authorization", "Bearer " + accessToken)
                .param("now", now)
                .param("tz", "Asia/Seoul"));
    }

    /** 설정 전체 교체 PUT — referenceDoseMg 와 알림 토글만 바꾸고 나머지는 기본값. */
    private void putSettings(String accessToken, int referenceDoseMg, boolean notifyCutoff,
                             boolean notifyBedtimeResidual, boolean notifyRecordReminder) throws Exception {
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                        "referenceDoseMg":%d,"isLearningEnabled":true,\
                        "notifications":{"cutoff":%b,"bedtimeResidual":%b,"recordReminder":%b}}"""
                        .formatted(referenceDoseMg, notifyCutoff, notifyBedtimeResidual, notifyRecordReminder)))
                .andExpect(status().isOk());
    }

    /** 카페 근처 알림만 켜고 나머지 알림 토글은 끈 설정. */
    private void enableOnlyCafeNearby(String accessToken) throws Exception {
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                        "referenceDoseMg":75,"isLearningEnabled":true,\
                        "notifications":{"cutoff":false,"bedtimeResidual":false,"recordReminder":false,"cafeNearby":true}}"""))
                .andExpect(status().isOk());
    }

    /** 과거 날짜들에 09:00 KST 100mg 기록을 하나씩 추가(평소 첫 기록 시각 09:00 패턴). */
    private void addHistory(String accessToken, String... dates) throws Exception {
        for (String date : dates) {
            addRecord(accessToken, 100, date + "T09:00:00+09:00");
        }
    }

    private void addRecord(String accessToken, int amountMg, String timestamp) throws Exception {
        mockMvc.perform(post("/api/caffeine-records").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":%d,"drinkName":"테스트","timestamp":"%s"}""".formatted(amountMg, timestamp)))
                .andExpect(status().isCreated());
    }

    @Test
    void 기본설정_기록없음_마감30분전_CUTOFF_항목1개() throws Exception {
        String accessToken = authToken("n@b.com", "알림");

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.now").value("2026-06-01T05:00:00Z"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("CUTOFF"))
                .andExpect(jsonPath("$.data.items[0].fireAt").value("2026-06-01T10:34:30.675Z"))
                .andExpect(jsonPath("$.data.items[0].title").value("섭취 마감 30분 전"))
                .andExpect(jsonPath("$.data.items[0].body", containsString("23:00")))
                .andExpect(jsonPath("$.data.items[0].body", containsString("75mg")))
                .andExpect(jsonPath("$.data.items[0].body", containsString("20:04")));
    }

    @Test
    void 토글_off면_빈목록() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        putSettings(accessToken, 75, false, true, true);

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 취침잔량_초과면_CUTOFF없고_잔량예고_1개() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        addRecord(accessToken, 400, "2026-06-01T13:00:00+09:00");

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("BEDTIME_RESIDUAL"))
                .andExpect(jsonPath("$.data.items[0].fireAt").value("2026-06-01T13:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].title").value("취침 60분 전 잔량 예고"))
                .andExpect(jsonPath("$.data.items[0].body", containsString("23:00")))
                .andExpect(jsonPath("$.data.items[0].body", containsString("100mg")));
    }

    @Test
    void 취침잔량_50mg미만이면_CUTOFF만() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        addRecord(accessToken, 100, "2026-06-01T09:00:00+09:00");

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("CUTOFF"))
                .andExpect(jsonPath("$.data.items[0].fireAt").value("2026-06-01T08:07:59.848Z"))
                .andExpect(jsonPath("$.data.items[0].body", containsString("17:37")));
    }

    @Test
    void 취침_60분전이_지났으면_잔량예고없음() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        addRecord(accessToken, 400, "2026-06-01T13:00:00+09:00");

        // now 22:30 KST: 취침 23:00 은 아직이지만 60분 전(22:00)은 지남. 마감은 ALREADY_EXCEEDED 라 없음
        plan(accessToken, "2026-06-01T22:30:00+09:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 잔량예고_토글off면_없음() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        putSettings(accessToken, 75, true, false, true);
        addRecord(accessToken, 400, "2026-06-01T13:00:00+09:00");

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 오늘기록없고_과거5일이면_리마인더와_CUTOFF_2개_fireAt순() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        addHistory(accessToken, FIVE_DAYS);

        plan(accessToken, NOW_08_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.now").value("2026-05-31T23:00:00Z"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].type").value("RECORD_REMINDER"))
                .andExpect(jsonPath("$.data.items[0].fireAt").value("2026-06-01T02:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].title").value("오늘 카페인 기록이 없어요"))
                .andExpect(jsonPath("$.data.items[0].body", containsString("09:00")))
                .andExpect(jsonPath("$.data.items[1].type").value("CUTOFF"))
                .andExpect(jsonPath("$.data.items[1].fireAt").value("2026-06-01T10:34:30.675Z"));
    }

    @Test
    void 오늘기록있으면_리마인더없음() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        addHistory(accessToken, FIVE_DAYS);
        addRecord(accessToken, 100, "2026-06-01T07:00:00+09:00");

        // 100mg @ 07:00 → 23:00 취침 시 10.88mg → CUTOFF 상태, 마감 18:18:17.035 KST
        plan(accessToken, NOW_08_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("CUTOFF"))
                .andExpect(jsonPath("$.data.items[0].fireAt").value("2026-06-01T08:48:17.035Z"))
                .andExpect(jsonPath("$.data.items[0].body", containsString("18:18")));
    }

    @Test
    void 리마인더시각이_지났으면_없음() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        addHistory(accessToken, FIVE_DAYS);

        // now 14:00 KST: 리마인더 11:00 은 지남
        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("CUTOFF"))
                .andExpect(jsonPath("$.data.items[0].fireAt").value("2026-06-01T10:34:30.675Z"));
    }

    @Test
    void 과거_기록일수가_5일미만이면_리마인더없음() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        addHistory(accessToken, FOUR_DAYS);

        plan(accessToken, NOW_08_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("CUTOFF"));
    }

    @Test
    void 리마인더_토글off면_없음() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        putSettings(accessToken, 75, true, true, false);
        addHistory(accessToken, FIVE_DAYS);

        plan(accessToken, NOW_08_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("CUTOFF"));
    }

    @Test
    void 마감까지_30분미만_남으면_빈목록() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        // now 20:00 KST: 마감 20:04:30 은 아직이지만 30분 전(19:34:30)은 이미 지남
        plan(accessToken, "2026-06-01T20:00:00+09:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 기준용량이_헤드룸_이하면_SAFE_ANYTIME_빈목록() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        putSettings(accessToken, 40, true, true, true); // 40mg ≤ 취침 헤드룸 50mg → 언제든 가능, 마감시각 없음

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 카페근처_기본값은_꺼짐이라_구간없음() throws Exception {
        String accessToken = authToken("n@b.com", "알림");

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cafeNearbyWindows").isEmpty());
    }

    @Test
    void 카페근처_기록없음_오늘은_마감부터_하루경계까지_내일_기본구간도() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        enableOnlyCafeNearby(accessToken);

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.cafeNearbyWindows.length()").value(2))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].reason").value("CUTOFF_PASSED"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].activeFrom").value("2026-06-01T11:04:30.675Z"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].activeUntil").value("2026-06-01T20:00:00Z"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].title").value("카페 근처예요"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].body", containsString("20:04")))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].body", containsString("75mg")))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].body", containsString("50mg")))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[1].reason").value("CUTOFF_PASSED"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[1].activeFrom").value("2026-06-02T11:04:30.675Z"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[1].activeUntil").value("2026-06-02T20:00:00Z"));
    }

    @Test
    void 카페근처_취침잔량_초과면_지금부터_ALREADY_EXCEEDED() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        enableOnlyCafeNearby(accessToken);
        addRecord(accessToken, 400, "2026-06-01T13:00:00+09:00");

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].reason").value("ALREADY_EXCEEDED"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].activeFrom").value("2026-06-01T05:00:00Z"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].activeUntil").value("2026-06-01T20:00:00Z"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].body", containsString("100mg")))
                // 다음 날은 오늘 기록과 무관한 기본 구간
                .andExpect(jsonPath("$.data.cafeNearbyWindows[1].reason").value("CUTOFF_PASSED"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[1].activeFrom").value("2026-06-02T11:04:30.675Z"));
    }

    @Test
    void 카페근처_하루권장량_초과면_지금부터_DAILY_LIMIT_EXCEEDED() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        enableOnlyCafeNearby(accessToken);
        addRecord(accessToken, 450, "2026-06-01T05:30:00+09:00");

        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].reason").value("DAILY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].activeFrom").value("2026-06-01T05:00:00Z"))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].body", containsString("450mg")))
                .andExpect(jsonPath("$.data.cafeNearbyWindows[0].body", containsString("400mg")));
    }

    @Test
    void 카페근처_기준용량이_헤드룸_이하면_구간없음() throws Exception {
        String accessToken = authToken("n@b.com", "알림");
        mockMvc.perform(put("/api/settings").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"halfLife":5.0,"condition":0,"bedtimeHour":23,"bedtimeMinute":0,\
                        "referenceDoseMg":40,"isLearningEnabled":true,\
                        "notifications":{"cutoff":true,"bedtimeResidual":true,"recordReminder":true,"cafeNearby":true}}"""))
                .andExpect(status().isOk());

        // 40mg ≤ 취침 헤드룸 50mg → 마감 없음(SAFE_ANYTIME)이고 400mg 이하 → 오늘도 내일도 구간 없음
        plan(accessToken, NOW_14_KST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cafeNearbyWindows").isEmpty());
    }

    @Test
    void 잘못된_tz는_400_INVALID_PARAMETER() throws Exception {
        String accessToken = authToken("n@b.com", "알림");

        mockMvc.perform(get("/api/notifications/plan").header("Authorization", "Bearer " + accessToken)
                .param("now", NOW_14_KST)
                .param("tz", "Not/AZone"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    void 인증없으면_401() throws Exception {
        mockMvc.perform(get("/api/notifications/plan")
                .param("now", NOW_14_KST).param("tz", "Asia/Seoul"))
                .andExpect(status().isUnauthorized());
    }
}
