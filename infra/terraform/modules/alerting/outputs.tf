output "alert_topic_arn" {
  value = aws_sns_topic.alerts.arn
}

output "heartbeat_topic_arn" {
  value = aws_sns_topic.heartbeat.arn
}
