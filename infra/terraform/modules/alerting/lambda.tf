# Lambda caffeine-alert-relay: SNS → Slack, 하트비트 → CloudWatch 메트릭 (docs/alerting-plan.md §5.2·§6). VPC 밖에 둔다.
# 코드(monitoring/lambda/alert-relay)를 고친 뒤 terraform apply 하면 새 코드로 배포된다.
#
# Slack 수신 웹훅 URL 은 Terraform 이 다루지 않는다 — state 에 남기지 않으려고 SSM SecureString 에 직접 넣는다
# (docs/ops/경보-AWS-설정.md). 파라미터가 없으면 Slack 전달만 실패하고(오류 알람 → 이메일) 하트비트는 정상이다.

locals {
  function_name     = "caffeine-alert-relay"
  webhook_parameter = "/caffeine/alert-relay/slack-webhook"
  metric_namespace  = "Caffeine/Monitoring"
}

# 테스트 파일은 넣지 않는다. 의존성이 없어 두 파일이 전부다
data "archive_file" "relay" {
  type        = "zip"
  output_path = "${path.root}/.build/alert-relay.zip"

  source {
    filename = "index.mjs"
    content  = file("${var.lambda_source_dir}/index.mjs")
  }

  source {
    filename = "relay.mjs"
    content  = file("${var.lambda_source_dir}/relay.mjs")
  }
}

resource "aws_iam_role" "relay" {
  name        = "caffeine-alert-relay"
  description = "caffeine alert-relay Lambda - Slack relay and heartbeat metric"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# 자기 로그, Caffeine/Monitoring 네임스페이스 메트릭, 웹훅 파라미터 읽기만
resource "aws_iam_role_policy" "relay" {
  name = "caffeine-alert-relay"
  role = aws_iam_role.relay.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "OwnLogsOnly"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.relay.arn}:*"
      },
      {
        Sid       = "HeartbeatMetricOnly"
        Effect    = "Allow"
        Action    = "cloudwatch:PutMetricData"
        Resource  = "*"
        Condition = { StringEquals = { "cloudwatch:namespace" = local.metric_namespace } }
      },
      {
        Sid      = "SlackWebhookParameterOnly"
        Effect   = "Allow"
        Action   = "ssm:GetParameter"
        Resource = "arn:aws:ssm:${var.region}:${local.account_id}:parameter${local.webhook_parameter}"
      },
    ]
  })
}

resource "aws_cloudwatch_log_group" "relay" {
  name              = "/aws/lambda/${local.function_name}"
  retention_in_days = 14
}

resource "aws_lambda_function" "relay" {
  function_name = local.function_name
  description   = "caffeine alerts: SNS to Slack, heartbeat to CloudWatch (docs/alerting-plan.md)"
  role          = aws_iam_role.relay.arn

  runtime       = "nodejs22.x"
  architectures = ["arm64"]
  handler       = "index.handler"
  memory_size   = 128
  timeout       = 10

  filename         = data.archive_file.relay.output_path
  source_code_hash = data.archive_file.relay.output_base64sha256

  environment {
    variables = {
      HEARTBEAT_TOPIC_ARN = aws_sns_topic.heartbeat.arn
      SLACK_WEBHOOK_PARAM = local.webhook_parameter
      METRIC_NAMESPACE    = local.metric_namespace
    }
  }

  # 로그 그룹을 먼저 만들어야 Lambda 가 보관 기간 없는 그룹을 스스로 만들지 않는다
  depends_on = [aws_cloudwatch_log_group.relay, aws_iam_role_policy.relay]
}

# 두 토픽을 같은 함수가 구독하고 TopicArn 으로 분기한다
resource "aws_lambda_permission" "sns" {
  for_each = { alerts = aws_sns_topic.alerts.arn, heartbeat = aws_sns_topic.heartbeat.arn }

  statement_id  = "sns-caffeine-${each.key}"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.relay.function_name
  principal     = "sns.amazonaws.com"
  source_arn    = each.value
}

resource "aws_sns_topic_subscription" "relay" {
  for_each = { alerts = aws_sns_topic.alerts.arn, heartbeat = aws_sns_topic.heartbeat.arn }

  topic_arn = each.value
  protocol  = "lambda"
  endpoint  = aws_lambda_function.relay.arn

  depends_on = [aws_lambda_permission.sns]
}

resource "aws_cloudwatch_metric_alarm" "relay_errors" {
  alarm_name          = "caffeine-alert-relay-errors"
  alarm_description   = "경보 전달 Lambda 오류. Slack 으로 경보가 가지 않을 수 있다(이메일은 영향 없음). 경보-AWS-설정.md 문제 해결"
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.relay.function_name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_action
  ok_actions          = local.alarm_action
}

# Prometheus·Alertmanager·SNS 엔드포인트·IAM·Lambda 중 어느 하나라도 끊기면 15분 안에 울린다. EC2 전체가 멈춘 경우도 잡는다.
# Alertmanager 가 하트비트를 보내기 시작한 뒤에 만든다(heartbeat_alarm_enabled) — 그 전에 만들면 바로 울린다
resource "aws_cloudwatch_metric_alarm" "heartbeat_missing" {
  count = var.heartbeat_alarm_enabled ? 1 : 0

  alarm_name          = "caffeine-heartbeat-missing"
  alarm_description   = "경보 경로 하트비트가 15분 없다. 서버 다운이 아니라 경보 경로 어딘가가 끊긴 것일 수 있다. 운영 가이드 '경보' 절 점검 순서"
  namespace           = local.metric_namespace
  metric_name         = "Heartbeat"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 3
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = local.alarm_action
  ok_actions          = local.alarm_action
}
