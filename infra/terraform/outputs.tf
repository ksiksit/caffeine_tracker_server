output "ec2_public_ip" {
  description = "EC2 고정 IP"
  value       = aws_eip.app.public_ip
}

output "ec2_instance_id" {
  value = aws_instance.app.id
}

output "ssh_command" {
  description = "개인 키 경로는 공개 키를 만든 곳에 맞춘다"
  value       = "ssh -i ~/.ssh/caffeine-key ec2-user@${aws_eip.app.public_ip}"
}

output "db_endpoint" {
  value = aws_db_instance.main.address
}

output "db_tunnel_command" {
  description = "PC 에서 RDS 에 접속할 때 여는 SSH 터널 (PC 의 13306 → RDS 3306)"
  value       = "ssh -i ~/.ssh/caffeine-key -N -L 13306:${aws_db_instance.main.address}:3306 ec2-user@${aws_eip.app.public_ip}"
}

output "ec2_egress" {
  description = "EC2 에서 인터넷으로 나가는 길"
  value       = var.ec2_egress_open ? "열림(443) — 설치·패치가 끝나면 terraform apply 로 닫는다" : "닫힘"
}

output "env_file" {
  description = "EC2 의 .env 에 넣을 값. DB_PASSWORD·JWT_SECRET·GRAFANA_ADMIN_PASSWORD 는 직접 넣는다(.env.example)"
  value = join("\n", concat(
    [
      "DB_URL=jdbc:mysql://${aws_db_instance.main.address}:3306/${aws_db_instance.main.db_name}?serverTimezone=UTC&characterEncoding=UTF-8&useUnicode=true&allowPublicKeyRetrieval=true&useSSL=false",
      "DB_USERNAME=${aws_db_instance.main.username}",
    ],
    var.alerting_enabled ? [
      "ALERT_TOPIC_ARN=${module.alerting[0].alert_topic_arn}",
      "HEARTBEAT_TOPIC_ARN=${module.alerting[0].heartbeat_topic_arn}",
      "AWS_REGION=${var.region}",
    ] : [],
  ))
}
