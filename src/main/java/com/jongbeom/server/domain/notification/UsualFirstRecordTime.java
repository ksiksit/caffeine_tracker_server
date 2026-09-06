package com.jongbeom.server.domain.notification;

import com.jongbeom.server.domain.calc.Pharmacokinetics;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * "평소 첫 기록 시각" — 과거 기록을 로컬 하루(차트 시작 05:00 경계, 05:00 이전은 전날)로 묶어
 * 날마다 첫 기록이 그날 05:00 으로부터 얼마나 뒤인지 구한 뒤 그 중앙값을 낸다. 기록 리마인더(RECORD_REMINDER)의 기준.
 * 하루 시작 산술은 {@code LocalCalendar.chartStart}(자정 + 5h)와 동일하게 둔다.
 */
public final class UsualFirstRecordTime {

    private static final int DAY_START_HOUR = Pharmacokinetics.CHART_START_HOUR;

    private UsualFirstRecordTime() {
    }

    /**
     * @param history 과거 기록 시각(오늘 제외). 순서 무관
     * @param minDays 기록이 있는 날이 이 수 미만이면 empty
     * @return 날별 첫 기록 오프셋(그날 05:00 기준)의 중앙값. 짝수 개면 가운데 둘의 평균
     */
    public static Optional<Duration> median(List<Instant> history, ZoneId zone, int minDays) {
        Map<LocalDate, Duration> firstOffsetByDay = new TreeMap<>();
        for (Instant ts : history) {
            LocalDate day = ts.atZone(zone).minusHours(DAY_START_HOUR).toLocalDate();
            Instant dayStart = day.atStartOfDay(zone).plusHours(DAY_START_HOUR).toInstant();
            Duration offset = Duration.between(dayStart, ts);
            firstOffsetByDay.merge(day, offset, (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        if (firstOffsetByDay.isEmpty() || firstOffsetByDay.size() < minDays) {
            return Optional.empty();
        }
        List<Duration> offsets = new ArrayList<>(firstOffsetByDay.values());
        Collections.sort(offsets);
        int n = offsets.size();
        Duration median = n % 2 == 1
                ? offsets.get(n / 2)
                : offsets.get(n / 2 - 1).plus(offsets.get(n / 2)).dividedBy(2);
        return Optional.of(median);
    }
}
