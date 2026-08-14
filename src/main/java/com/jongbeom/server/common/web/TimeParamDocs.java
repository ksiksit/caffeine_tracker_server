package com.jongbeom.server.common.web;

/**
 * 연산 엔드포인트 공통 {@code tz}/{@code now} 파라미터의 Swagger 문서 문자열.
 * 도메인마다 복붙되던 설명·예시를 한곳에서 관리한다 (§타임존 계약: CLAUDE.md).
 */
public final class TimeParamDocs {

    private TimeParamDocs() {
    }

    public static final String TZ_DESCRIPTION = "IANA 타임존";
    public static final String TZ_EXAMPLE = "Asia/Seoul";
    public static final String NOW_DESCRIPTION = "기준 시각(ISO-8601+오프셋)";
    public static final String NOW_EXAMPLE = "2026-06-01T14:30:00+09:00";
}
