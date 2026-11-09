// Lambda caffeine-alert-relay 진입점(handler: index.handler, 런타임 nodejs22.x). 배포: terraform apply (infra/terraform/modules/alerting/lambda.tf)
// AWS SDK v3 는 Lambda 런타임에 들어 있어 이 폴더에는 의존성이 없다. 로직은 relay.mjs 에 있고 여기서는 실제 클라이언트만 잇는다.
import { CloudWatchClient, PutMetricDataCommand } from "@aws-sdk/client-cloudwatch";
import { GetParameterCommand, SSMClient } from "@aws-sdk/client-ssm";
import { createHandler } from "./relay.mjs";

const cloudwatch = new CloudWatchClient({});
const ssm = new SSMClient({});

export const handler = createHandler({
    env: process.env,
    putHeartbeat: (namespace) =>
        cloudwatch.send(
            new PutMetricDataCommand({
                Namespace: namespace,
                MetricData: [{ MetricName: "Heartbeat", Value: 1, Unit: "Count" }],
            }),
        ),
    getParameter: async (name) => {
        const result = await ssm.send(new GetParameterCommand({ Name: name, WithDecryption: true }));
        return result.Parameter?.Value ?? "";
    },
    fetch: (...args) => globalThis.fetch(...args),
    log: (...args) => console.log(...args),
});
