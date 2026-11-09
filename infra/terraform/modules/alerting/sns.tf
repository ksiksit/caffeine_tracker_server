# 토픽 둘과 이메일 구독 (docs/alerting-plan.md §2). 하트비트를 따로 둔 것은 이메일로 가지 않게 하는 가장 단순한 방법이라서다.
# 암호화하지 않는다 — CloudWatch 알람은 AWS 관리형 키로 암호화된 토픽에 발행하지 못하고, 메시지에 개인정보가 없다.

data "aws_caller_identity" "current" {}

locals {
  account_id   = data.aws_caller_identity.current.account_id
  topic_arns   = [aws_sns_topic.alerts.arn, aws_sns_topic.heartbeat.arn]
  alarm_action = [aws_sns_topic.alerts.arn] # 알람은 울릴 때와 풀릴 때 모두 알린다
}

resource "aws_sns_topic" "alerts" {
  name = "caffeine-alerts"
}

resource "aws_sns_topic" "heartbeat" {
  name = "caffeine-heartbeat"
}

# 계정 관리 권한 + CloudWatch 알람의 발행 권한(같은 계정에서 온 것만)
resource "aws_sns_topic_policy" "alerts" {
  arn = aws_sns_topic.alerts.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AccountOwnerManage"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${local.account_id}:root" }
        Action = [
          "SNS:GetTopicAttributes",
          "SNS:SetTopicAttributes",
          "SNS:AddPermission",
          "SNS:RemovePermission",
          "SNS:DeleteTopic",
          "SNS:Subscribe",
          "SNS:ListSubscriptionsByTopic",
          "SNS:Publish",
        ]
        Resource = aws_sns_topic.alerts.arn
      },
      {
        Sid       = "CloudWatchAlarmsPublish"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.alerts.arn
        Condition = { StringEquals = { "aws:SourceAccount" = local.account_id } }
      },
    ]
  })
}

# 이메일은 SNS 가 직접 보낸다 — Lambda·Slack 이 고장 나도 온다. 메일의 Confirm subscription 을 눌러야 받기 시작한다
resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}
