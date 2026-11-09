# 경보용 AWS 자원. 설계: docs/alerting-plan.md, 절차·확인: docs/ops/경보-AWS-설정.md
# alerting_enabled = false 로 apply 하면 이 모듈의 자원만 지워진다 (비용 대부분은 SNS 엔드포인트다).
module "alerting" {
  source = "./modules/alerting"
  count  = var.alerting_enabled ? 1 : 0

  region      = var.region
  alert_email = var.alert_email

  vpc_id                = aws_vpc.main.id
  subnet_id             = aws_subnet.public.id
  ec2_security_group_id = aws_security_group.ec2.id
  ec2_instance_id       = aws_instance.app.id
  ec2_instance_type     = var.instance_type
  ec2_role_name         = aws_iam_role.ec2.name
  db_identifier         = aws_db_instance.main.identifier

  heartbeat_alarm_enabled = var.heartbeat_alarm_enabled
  ec2_auto_recover        = var.ec2_auto_recover
  lambda_source_dir       = "${path.module}/../../monitoring/lambda/alert-relay"
}
