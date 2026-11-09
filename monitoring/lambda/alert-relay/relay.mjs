// alert-relay 의 로직. AWS SDK 를 직접 쓰지 않고 바깥에서 받는다(index.mjs 가 연결한다) — 그래서 SDK 없이 테스트된다.
// 설계: docs/alerting-plan.md §6
//
// SNS 레코드 하나를 세 가지로 나눈다
//   heartbeat 토픽에서 온 것      → CloudWatch 메트릭 Heartbeat 를 1 올린다 (끊기면 CloudWatch 알람이 울린다, §5.2)
//   CloudWatch 알람이 보낸 JSON    → 이름·상태·사유로 한국어 문장을 만들어 Discord 로
//   그 외(Alertmanager 가 만든 문장) → 제목과 본문을 그대로 Discord 로. 문장은 Alertmanager 템플릿이 원본이다
//
// 웹훅 URL 은 아는 사람이 누구나 글을 쓸 수 있어 비밀로 다룬다. SSM 에서 읽어 실행 환경에 캐시하고, 로그·오류 메시지에 남기지 않는다.

export const DISCORD_LIMIT = 2000; // Discord 메시지 content 최대 글자 수
export const WEBHOOK_CACHE_MS = 5 * 60 * 1000; // SSM 값을 바꾸면 늦어도 5분 뒤에는 새 값을 쓴다
export const TRUNCATED_SUFFIX = "\n…(길어서 잘렸다. 이메일에서 전체 확인)";
const WEBHOOK_PREFIXES = ["https://discord.com/api/webhooks/", "https://discordapp.com/api/webhooks/"];
const KST_OFFSET_MS = 9 * 60 * 60 * 1000; // 한국은 일광 절약 시간이 없어 고정 오프셋으로 충분하다

const STATE_ICON = { ALARM: "🔴", OK: "✅", INSUFFICIENT_DATA: "⚪" };
const STATE_LABEL = { ALARM: "발생", OK: "해결", INSUFFICIENT_DATA: "데이터 부족" };

/** "2026-11-09T05:03:00.000+0000" 같은 시각을 "11-09 14:03 KST" 로. 읽지 못하면 원문 그대로. */
export function kst(timestamp) {
    const ms = Date.parse(timestamp);
    if (Number.isNaN(ms)) {
        return String(timestamp ?? "");
    }
    const d = new Date(ms + KST_OFFSET_MS);
    const two = (n) => String(n).padStart(2, "0");
    return `${two(d.getUTCMonth() + 1)}-${two(d.getUTCDate())} ${two(d.getUTCHours())}:${two(d.getUTCMinutes())} KST`;
}

/** 코드 포인트 기준으로 limit 이하가 되게 자른다(이모지·한글이 반쪽으로 잘리지 않게). */
export function truncate(text, limit = DISCORD_LIMIT) {
    const chars = Array.from(text);
    if (chars.length <= limit) {
        return text;
    }
    const keep = limit - Array.from(TRUNCATED_SUFFIX).length;
    return chars.slice(0, keep).join("") + TRUNCATED_SUFFIX;
}

function parseCloudWatchAlarm(message) {
    try {
        const parsed = JSON.parse(message);
        return parsed && typeof parsed === "object" && typeof parsed.AlarmName === "string" ? parsed : null;
    } catch {
        return null;
    }
}

/** SNS 레코드를 무엇을 할지로 바꾼다. 부수 효과가 없다. */
export function classify(record, heartbeatTopicArn) {
    const sns = record?.Sns ?? {};
    if (heartbeatTopicArn && sns.TopicArn === heartbeatTopicArn) {
        return { kind: "heartbeat" };
    }
    const message = typeof sns.Message === "string" ? sns.Message : "";
    const alarm = parseCloudWatchAlarm(message);
    if (alarm) {
        const state = alarm.NewStateValue;
        const lines = [
            `${STATE_ICON[state] ?? "🟠"} **[CloudWatch] ${alarm.AlarmName}** · ${STATE_LABEL[state] ?? state ?? "?"}`,
        ];
        if (alarm.AlarmDescription) lines.push(`내용: ${alarm.AlarmDescription}`);
        if (alarm.NewStateReason) lines.push(`사유: ${alarm.NewStateReason}`);
        if (alarm.StateChangeTime) lines.push(`시각: ${kst(alarm.StateChangeTime)}`);
        return { kind: "discord", label: `cloudwatch ${alarm.AlarmName} ${state}`, content: truncate(lines.join("\n")) };
    }
    const subject = typeof sns.Subject === "string" ? sns.Subject.trim() : "";
    const body = message.trim();
    const content = [subject ? `**${subject}**` : "", body].filter(Boolean).join("\n") || "(빈 경보 메시지)";
    return { kind: "discord", label: subject || "alertmanager", content: truncate(content) };
}

/**
 * Lambda 핸들러를 만든다.
 * @param deps.env           HEARTBEAT_TOPIC_ARN · DISCORD_WEBHOOK_PARAM · METRIC_NAMESPACE
 * @param deps.putHeartbeat  (namespace) => Promise — CloudWatch PutMetricData
 * @param deps.getParameter  (name) => Promise<string> — SSM SecureString 복호화 값
 * @param deps.fetch         WHATWG fetch
 * @param deps.log           (...args) => void
 * @param deps.now           () => epoch ms (테스트에서 시간을 고정한다)
 */
export function createHandler({ env, putHeartbeat, getParameter, fetch, log = () => {}, now = Date.now }) {
    // 실행 환경 안에서 WEBHOOK_CACHE_MS 동안 재사용한다. 실패하면 비워 다음 호출이 다시 읽는다
    let webhookPromise = null;
    let webhookReadAt = 0;

    async function webhookUrl() {
        if (!webhookPromise || now() - webhookReadAt >= WEBHOOK_CACHE_MS) {
            webhookReadAt = now();
            webhookPromise = (async () => {
                const url = String(await getParameter(env.DISCORD_WEBHOOK_PARAM)).trim();
                if (!WEBHOOK_PREFIXES.some((prefix) => url.startsWith(prefix))) {
                    // 값이 바뀌어 다른 곳으로 보내는 일을 막는다. 값 자체는 남기지 않는다
                    throw new Error(`SSM ${env.DISCORD_WEBHOOK_PARAM} 의 값이 Discord 웹훅 URL 형식이 아니다`);
                }
                return url;
            })();
            const pending = webhookPromise;
            pending.catch(() => {
                if (webhookPromise === pending) {
                    webhookPromise = null; // 그사이 새로 읽기 시작한 값은 지우지 않는다
                }
            });
        }
        return webhookPromise;
    }

    async function postToDiscord(content) {
        const response = await fetch(await webhookUrl(), {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            // allowed_mentions: 경보 문구에 @everyone 같은 글자가 섞여도 아무도 호출하지 않는다
            body: JSON.stringify({ content, allowed_mentions: { parse: [] } }),
        });
        if (!response.ok) {
            // 예외를 던지면 SNS 가 Lambda 를 다시 호출한다(비동기 호출 기본 재시도). 오류가 계속되면 Lambda 오류 알람이 이메일로 알린다
            throw new Error(`Discord 응답 ${response.status}`);
        }
    }

    return async function handler(event) {
        for (const record of event?.Records ?? []) {
            const action = classify(record, env.HEARTBEAT_TOPIC_ARN);
            if (action.kind === "heartbeat") {
                await putHeartbeat(env.METRIC_NAMESPACE || "Caffeine/Monitoring");
                log("heartbeat");
            } else {
                await postToDiscord(action.content);
                log("discord", action.label);
            }
        }
    };
}
