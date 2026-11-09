# EC2 바깥에서 지켜보는 CloudWatch 알람 (docs/alerting-plan.md §5). EC2·RDS 의 설정은 바꾸지 않는다.

# 상태 검사 지표는 1분 단위로 무료 제공된다. 인스턴스를 일부러 멈추면 지표가 끊겨 '데이터 부족'이 된다 — 그 경우는 하트비트 알람이 맡는다
resource "aws_cloudwatch_metric_alarm" "ec2_status_check" {
  alarm_name          = "caffeine-ec2-status-check"
  alarm_description   = "운영 EC2 상태 검사 실패 2분. 인스턴스가 멈췄거나 응답하지 않는다. 운영 가이드 '경보' 절"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed"
  dimensions          = { InstanceId = var.ec2_instance_id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  alarm_actions       = local.alarm_action
  ok_actions          = local.alarm_action
}

# AWS 쪽 하드웨어 문제(시스템 상태 검사)일 때만 같은 설정으로 다른 호스트에서 다시 띄운다. 인스턴스 안의 문제는 대상이 아니다
resource "aws_cloudwatch_metric_alarm" "ec2_auto_recover" {
  count = var.ec2_auto_recover ? 1 : 0

  alarm_name          = "caffeine-ec2-auto-recover"
  alarm_description   = "운영 EC2 시스템 상태 검사 실패 2분 — 인스턴스 자동 복구를 실행했다"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_System"
  dimensions          = { InstanceId = var.ec2_instance_id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  alarm_actions       = concat(["arn:aws:automate:${var.region}:ec2:recover"], local.alarm_action)
  ok_actions          = local.alarm_action
}

# 버스트형(t 계열)은 크레딧이 바닥나면 기준 성능으로 묶여 응답이 느려진다
resource "aws_cloudwatch_metric_alarm" "ec2_cpu_credit" {
  count = startswith(var.ec2_instance_type, "t") ? 1 : 0

  alarm_name          = "caffeine-ec2-cpu-credit"
  alarm_description   = "운영 EC2 CPU 크레딧 20 미만 15분. 곧 기준 성능으로 묶여 느려진다"
  namespace           = "AWS/EC2"
  metric_name         = "CPUCreditBalance"
  dimensions          = { InstanceId = var.ec2_instance_id }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 3
  threshold           = 20
  comparison_operator = "LessThanThreshold"
  alarm_actions       = local.alarm_action
  ok_actions          = local.alarm_action
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "caffeine-rds-free-storage"
  alarm_description   = "운영 RDS 여유 저장공간 2GiB 미만 15분. 가득 차면 쓰기가 실패한다"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = var.db_identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 3
  threshold           = 2147483648
  comparison_operator = "LessThanThreshold"
  alarm_actions       = local.alarm_action
  ok_actions          = local.alarm_action
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "caffeine-rds-cpu"
  alarm_description   = "운영 RDS CPU 80% 초과 15분. 느린 쿼리나 과부하"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = var.db_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = local.alarm_action
  ok_actions          = local.alarm_action
}
