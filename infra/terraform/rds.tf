# DB. 비공개(인터넷 경로 없는 서브넷, EC2 보안 그룹에서 오는 3306 만). 스키마는 만들지 않는다 — docs/db/db-schema.sql 을 직접 적용.

resource "aws_db_subnet_group" "main" {
  name        = "caffeine-db"
  description = "caffeine RDS subnets (no internet route)"
  subnet_ids  = [aws_subnet.db_a.id, aws_subnet.db_b.id]
}

data "aws_caller_identity" "current" {}

# 비밀번호는 apply 전에 SSM SecureString /caffeine/db-password 에 직접 넣어 둔다 (docs/ops/기반-AWS-설정.md).
# 실행할 때마다 읽기만 하고 state·plan 에 남기지 않는다. EC2 의 .env 에 넣을 때도 같은 파라미터에서 꺼낸다.
ephemeral "aws_ssm_parameter" "db_password" {
  arn = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/caffeine/db-password"

  lifecycle {
    # 특수문자는 RDS(/ @ " 공백 금지)·.env·compose 변수 치환에서 각각 다르게 깨지므로 받지 않는다
    postcondition {
      condition     = can(regex("^[A-Za-z0-9]{16,41}$", self.value))
      error_message = "SSM /caffeine/db-password 값은 영문·숫자 16~41자여야 한다 (생성: openssl rand -hex 16)."
    }
  }
}

resource "aws_db_instance" "main" {
  identifier     = "caffeine-db"
  engine         = "mysql"
  engine_version = var.db_engine_version
  # 표준 지원이 끝난 버전이면 연장 지원 요금에 가입되는 대신 생성이 실패하게 한다
  engine_lifecycle_support = "open-source-rds-extended-support-disabled"
  instance_class           = var.db_instance_class

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "caffeine_tracker"
  username = "caffeine"
  # 쓰기 전용: RDS 로만 보내고 state·plan 에 남기지 않는다. db_password_version 이 바뀔 때만 다시 보낸다
  password_wo         = ephemeral.aws_ssm_parameter.db_password.value
  password_wo_version = var.db_password_version

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  availability_zone      = aws_subnet.db_a.availability_zone # EC2 와 같은 영역 (영역 간 전송 요금·지연 없음)
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period   = 1
  delete_automated_backups  = true
  skip_final_snapshot       = !var.db_final_snapshot
  final_snapshot_identifier = var.db_final_snapshot ? "caffeine-db-final" : null
  apply_immediately         = true # 유형·비밀번호 변경을 유지 관리 시간까지 미루지 않는다
}
