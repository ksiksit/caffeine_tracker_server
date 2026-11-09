variable "region" {
  type = string
}

variable "alert_email" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  description = "EC2 가 있는 서브넷. SNS 엔드포인트를 여기에 둔다"
  type        = string
}

variable "ec2_security_group_id" {
  type = string
}

variable "ec2_instance_id" {
  type = string
}

variable "ec2_instance_type" {
  type = string
}

variable "ec2_role_name" {
  description = "EC2 인스턴스 역할. 두 토픽 발행 정책을 붙인다"
  type        = string
}

variable "db_identifier" {
  type = string
}

variable "heartbeat_alarm_enabled" {
  type = bool
}

variable "ec2_auto_recover" {
  type = bool
}

variable "lambda_source_dir" {
  description = "index.mjs·relay.mjs 가 있는 폴더 (monitoring/lambda/alert-relay)"
  type        = string
}
