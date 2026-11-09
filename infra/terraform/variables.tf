# 값은 terraform.tfvars 에 적는다(terraform.tfvars.example 참고). 비밀(DB 비밀번호·Slack 웹훅)은 변수로 받지 않고
# SSM 파라미터 스토어에 직접 넣어 둔다 — state 와 tfvars 에 비밀이 남지 않는다.

variable "region" {
  description = "리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "ssh_cidr" {
  description = "SSH 를 허용할 내 PC 주소 (예: 203.0.113.7/32). 확인: curl -s https://checkip.amazonaws.com"
  type        = string

  validation {
    condition     = can(cidrhost(var.ssh_cidr, 0)) && !endswith(var.ssh_cidr, "/0")
    error_message = "주소/길이 형식이어야 하고(예: 203.0.113.7/32), 전체(/0)로는 열지 않는다."
  }
}

variable "ssh_public_key" {
  description = "EC2 에 넣을 SSH 공개 키 내용 (예: ~/.ssh/caffeine-key.pub 의 한 줄). 개인 키는 PC 에만 둔다"
  type        = string

  validation {
    condition     = can(regex("^(ssh-ed25519|ssh-rsa) ", var.ssh_public_key))
    error_message = "ssh-ed25519 또는 ssh-rsa 로 시작하는 공개 키 한 줄이어야 한다 (개인 키를 넣지 않는다)."
  }
}

variable "api_cidr" {
  description = "API(8080)를 허용할 주소. 앱이 어디서나 접속하므로 기본은 전체"
  type        = string
  default     = "0.0.0.0/0"
}

variable "instance_type" {
  description = "EC2 유형. CI 가 amd64 이미지만 만들므로 x86_64 유형만 쓴다"
  type        = string
  default     = "t3.small"

  validation {
    condition     = !can(regex("^[a-z]+[0-9]+[a-z]*g[a-z]*\\.", var.instance_type))
    error_message = "arm(Graviton) 유형이다 (t4g·m7g 등). 앱 이미지가 amd64 뿐이라 뜨지 않는다."
  }
}

variable "ec2_egress_open" {
  description = "EC2 에서 인터넷으로 나가는 443 을 연다. 첫 부팅의 Docker 설치와 OS 패치 때만 true, 평소에는 false"
  type        = bool
  default     = false
}

variable "db_instance_class" {
  description = "RDS 유형"
  type        = string
  default     = "db.t4g.micro"
}

variable "db_engine_version" {
  description = "MySQL 버전. 8.0 은 2026-07-31 표준 지원이 끝나 연장 지원 요금이 붙으므로 생성이 거부되게 해 두었다"
  type        = string
  default     = "8.4"
}

variable "db_password_version" {
  description = "RDS 비밀번호를 바꿀 때: SSM 파라미터 /caffeine/db-password 값을 바꾼 뒤 이 숫자를 1 올려 apply 한다"
  type        = number
  default     = 1
}

variable "db_final_snapshot" {
  description = "true 면 destroy 때 마지막 스냅숏 caffeine-db-final 을 남긴다(보관 요금). false 면 데이터가 지워진다"
  type        = bool
  default     = false
}

variable "alerting_enabled" {
  description = "경보용 자원(SNS·엔드포인트·Lambda·알람)을 만든다. false 로 바꿔 apply 하면 경보 자원만 지워진다"
  type        = bool
  default     = true
}

variable "alert_email" {
  description = "경보를 받을 이메일. alerting_enabled 가 true 면 필요하다"
  type        = string
  default     = null

  validation {
    condition     = !var.alerting_enabled || can(regex("^[^@ ]+@[^@ ]+$", var.alert_email))
    error_message = "alerting_enabled 가 true 면 alert_email 에 이메일 주소를 넣는다."
  }
}

variable "heartbeat_alarm_enabled" {
  description = "하트비트 끊김 알람을 만든다. EC2 에서 Alertmanager 를 켠 뒤에 true 로 바꾼다 — 그 전에 만들면 15분 뒤 바로 울린다"
  type        = bool
  default     = false
}

variable "ec2_auto_recover" {
  description = "AWS 쪽 하드웨어 문제(시스템 상태 검사 실패)일 때 인스턴스를 자동 복구하는 알람도 만든다"
  type        = bool
  default     = false
}
