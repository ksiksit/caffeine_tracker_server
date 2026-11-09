// relay.mjs 단위 테스트. 실행: node --test monitoring/lambda/alert-relay/relay.test.mjs (monitoring/check.sh·CI 도 돌린다)
// AWS SDK·네트워크 없이 돈다. Alertmanager 문장은 템플릿의 기대 렌더링 결과(monitoring/alertmanager/testdata)를 그대로 입력으로 쓴다 —
// 템플릿을 바꾸면 이 테스트가 Discord 로 나가는 모양까지 함께 확인한다.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it, mock } from "node:test";
import { classify, createHandler, DISCORD_LIMIT, kst, TRUNCATED_SUFFIX, truncate, WEBHOOK_CACHE_MS } from "./relay.mjs";

const ALERTS = "arn:aws:sns:ap-northeast-2:123456789012:caffeine-alerts";
const HEARTBEAT = "arn:aws:sns:ap-northeast-2:123456789012:caffeine-heartbeat";
const WEBHOOK = "https://discord.com/api/webhooks/123/secret-token";
const ENV = {
    HEARTBEAT_TOPIC_ARN: HEARTBEAT,
    DISCORD_WEBHOOK_PARAM: "/caffeine/alert-relay/discord-webhook",
    METRIC_NAMESPACE: "Caffeine/Monitoring",
};

/** Alertmanager 템플릿 기대 결과(제목 / --- / 본문)를 SNS 의 Subject·Message 로 나눈다 */
function renderedAlert(name) {
    const text = readFileSync(new URL(`../../alertmanager/testdata/${name}.expected.txt`, import.meta.url), "utf8");
    const [subject, ...rest] = text.split("\n---\n");
    return { subject, message: rest.join("\n---\n") };
}

function snsEvent(topicArn, message, subject) {
    return { Records: [{ EventSource: "aws:sns", Sns: { TopicArn: topicArn, Message: message, Subject: subject } }] };
}

function cloudWatchAlarm(state) {
    return JSON.stringify({
        AlarmName: "caffeine-ec2-status-check",
        AlarmDescription: "운영 EC2 상태 검사 실패 2분.",
        NewStateValue: state,
        NewStateReason: "Threshold Crossed: 2 datapoints were greater than or equal to the threshold (1.0).",
        StateChangeTime: "2026-11-09T05:03:00.000+0000",
        Region: "Asia Pacific (Seoul)",
    });
}

function setup({ status = 204, parameter = WEBHOOK } = {}) {
    const clock = { ms: 1_000_000 };
    const deps = {
        clock,
        now: () => clock.ms,
        env: ENV,
        putHeartbeat: mock.fn(async () => {}),
        getParameter: mock.fn(async () => parameter),
        fetch: mock.fn(async () => ({ ok: status >= 200 && status < 300, status })),
    };
    return { deps, handler: createHandler(deps) };
}

const sentBody = (deps, call = 0) => JSON.parse(deps.fetch.mock.calls[call].arguments[1].body);

describe("하트비트", () => {
    it("heartbeat 토픽이면 메트릭만 올리고 Discord 로 보내지 않는다", async () => {
        const { deps, handler } = setup();
        await handler(snsEvent(HEARTBEAT, "heartbeat", "heartbeat"));
        assert.equal(deps.putHeartbeat.mock.callCount(), 1);
        assert.deepEqual(deps.putHeartbeat.mock.calls[0].arguments, ["Caffeine/Monitoring"]);
        assert.equal(deps.fetch.mock.callCount(), 0);
        assert.equal(deps.getParameter.mock.callCount(), 0); // 웹훅 URL 도 읽지 않는다
    });
});

describe("Alertmanager 경보", () => {
    it("발생: 굵은 제목 다음 줄에 템플릿 본문을 그대로 보낸다", async () => {
        const { subject, message } = renderedAlert("firing");
        const { deps, handler } = setup();
        await handler(snsEvent(ALERTS, message, subject));
        assert.equal(deps.fetch.mock.callCount(), 1);
        assert.equal(deps.fetch.mock.calls[0].arguments[0], WEBHOOK);
        const body = sentBody(deps);
        assert.equal(body.content, `**[CRITICAL] High5xxRate**\n${message.trim()}`);
        assert.match(body.content, /🔴 서버 오류\(5xx\) 급증/);
        assert.deepEqual(body.allowed_mentions, { parse: [] }); // @everyone 등 호출 금지
    });

    it("해결 알림도 같은 방식으로 보낸다", async () => {
        const { subject, message } = renderedAlert("resolved");
        const { deps, handler } = setup();
        await handler(snsEvent(ALERTS, message, subject));
        assert.equal(sentBody(deps).content, `**[RESOLVED] High5xxRate**\n${message.trim()}`);
    });

    it("제목이 없어도 본문을 보낸다", () => {
        const action = classify({ Sns: { TopicArn: ALERTS, Message: "본문만" } }, HEARTBEAT);
        assert.deepEqual(action, { kind: "discord", label: "alertmanager", content: "본문만" });
    });
});

describe("CloudWatch 알람", () => {
    for (const [state, icon, label] of [
        ["ALARM", "🔴", "발생"],
        ["OK", "✅", "해결"],
        ["INSUFFICIENT_DATA", "⚪", "데이터 부족"],
    ]) {
        it(`${state}: 이름·상태·내용·사유·KST 시각으로 문장을 만든다`, () => {
            const action = classify({ Sns: { TopicArn: ALERTS, Message: cloudWatchAlarm(state), Subject: `${state}: "caffeine-ec2-status-check"` } }, HEARTBEAT);
            assert.equal(action.kind, "discord");
            assert.equal(
                action.content,
                [
                    `${icon} **[CloudWatch] caffeine-ec2-status-check** · ${label}`,
                    "내용: 운영 EC2 상태 검사 실패 2분.",
                    "사유: Threshold Crossed: 2 datapoints were greater than or equal to the threshold (1.0).",
                    "시각: 11-09 14:03 KST",
                ].join("\n"),
            );
        });
    }

    it("AlarmName 이 없는 JSON 은 CloudWatch 알람으로 보지 않는다", () => {
        const action = classify({ Sns: { TopicArn: ALERTS, Message: '{"foo":1}', Subject: "x" } }, HEARTBEAT);
        assert.equal(action.content, '**x**\n{"foo":1}');
    });
});

describe("길이 제한", () => {
    it("2000자를 넘으면 잘라서 끝에 안내를 붙인다 (한글·이모지를 반쪽으로 자르지 않는다)", () => {
        const long = "🔴가".repeat(1500); // 3000 코드 포인트
        const cut = truncate(long);
        assert.equal(Array.from(cut).length, DISCORD_LIMIT);
        assert.ok(cut.endsWith(TRUNCATED_SUFFIX));
        assert.ok(!cut.includes("�"));
    });

    it("2000자 이하는 그대로 둔다", () => {
        const exact = "가".repeat(DISCORD_LIMIT);
        assert.equal(truncate(exact), exact);
    });

    it("긴 Alertmanager 메시지도 Discord 로 갈 때 2000자 이하다", async () => {
        const { deps, handler } = setup();
        await handler(snsEvent(ALERTS, "내용 ".repeat(1000), "[WARNING] ErrorLogBurst"));
        assert.ok(Array.from(sentBody(deps).content).length <= DISCORD_LIMIT);
    });
});

describe("웹훅 URL", () => {
    it("SSM 값을 실행 환경에 캐시해 두 번째 호출부터는 읽지 않는다", async () => {
        const { deps, handler } = setup();
        await handler(snsEvent(ALERTS, "a", "[WARNING] A"));
        await handler(snsEvent(ALERTS, "b", "[WARNING] B"));
        assert.equal(deps.getParameter.mock.callCount(), 1);
        assert.deepEqual(deps.getParameter.mock.calls[0].arguments, ["/caffeine/alert-relay/discord-webhook"]);
        assert.equal(deps.fetch.mock.callCount(), 2);
    });

    it("캐시는 5분까지만 쓰고, 그 뒤에는 바뀐 값을 다시 읽는다", async () => {
        const { deps, handler } = setup();
        await handler(snsEvent(ALERTS, "a", "[WARNING] A"));
        deps.clock.ms += WEBHOOK_CACHE_MS - 1;
        await handler(snsEvent(ALERTS, "b", "[WARNING] B"));
        assert.equal(deps.getParameter.mock.callCount(), 1); // 5분 직전까지는 캐시

        deps.getParameter.mock.mockImplementation(async () => "https://discord.com/api/webhooks/999/new-token");
        deps.clock.ms += 1;
        await handler(snsEvent(ALERTS, "c", "[WARNING] C"));
        assert.equal(deps.getParameter.mock.callCount(), 2);
        assert.equal(deps.fetch.mock.calls[2].arguments[0], "https://discord.com/api/webhooks/999/new-token");
    });

    it("SSM 읽기가 실패하면 캐시하지 않고 다음 호출이 다시 읽는다", async () => {
        const { deps, handler } = setup();
        deps.getParameter.mock.mockImplementationOnce(async () => {
            throw new Error("throttled");
        });
        await assert.rejects(handler(snsEvent(ALERTS, "a", "[WARNING] A")), /throttled/);
        await handler(snsEvent(ALERTS, "b", "[WARNING] B"));
        assert.equal(deps.getParameter.mock.callCount(), 2);
        assert.equal(deps.fetch.mock.callCount(), 1);
    });

    it("Discord 웹훅 형식이 아니면 보내지 않고, 오류에 값을 남기지 않는다", async () => {
        const { deps, handler } = setup({ parameter: "https://evil.example.com/collect?token=abc" });
        await assert.rejects(handler(snsEvent(ALERTS, "a", "[WARNING] A")), (error) => {
            assert.match(error.message, /Discord 웹훅 URL 형식이 아니다/);
            assert.ok(!error.message.includes("evil.example.com"));
            return true;
        });
        assert.equal(deps.fetch.mock.callCount(), 0);
    });

    it("discordapp.com 웹훅도 받는다", async () => {
        const { deps, handler } = setup({ parameter: "https://discordapp.com/api/webhooks/1/x\n" });
        await handler(snsEvent(ALERTS, "a", "[WARNING] A"));
        assert.equal(deps.fetch.mock.calls[0].arguments[0], "https://discordapp.com/api/webhooks/1/x");
    });
});

describe("Discord 실패", () => {
    for (const status of [429, 500, 404]) {
        it(`응답 ${status} 이면 예외를 던져 SNS 가 다시 호출하게 한다 (오류에 URL 이 없다)`, async () => {
            const { handler } = setup({ status });
            await assert.rejects(handler(snsEvent(ALERTS, "a", "[CRITICAL] AppDown")), (error) => {
                assert.equal(error.message, `Discord 응답 ${status}`);
                assert.ok(!error.message.includes("secret-token"));
                return true;
            });
        });
    }
});

describe("KST 변환", () => {
    it("UTC 를 9시간 더한 시각으로, 날짜가 넘어가도 맞게 쓴다", () => {
        assert.equal(kst("2026-11-09T05:03:00.000+0000"), "11-09 14:03 KST");
        assert.equal(kst("2026-12-31T16:30:00Z"), "01-01 01:30 KST");
    });

    it("읽지 못하는 시각은 원문 그대로 둔다", () => {
        assert.equal(kst("어제"), "어제");
    });
});

describe("이벤트 모양", () => {
    it("레코드가 없거나 비어 있어도 실패하지 않는다", async () => {
        const { deps, handler } = setup();
        await handler({});
        await handler({ Records: [] });
        assert.equal(deps.fetch.mock.callCount() + deps.putHeartbeat.mock.callCount(), 0);
    });
});
