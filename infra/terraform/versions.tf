# 카페인 트래커 AWS 인프라. 절차: docs/ops/기반-AWS-설정.md (기반) · docs/ops/경보-AWS-설정.md (경보)
# PC 에서 관리자 권한 자격 증명으로 실행한다. state 는 이 폴더의 terraform.tfstate (레포에 올리지 않는다).
terraform {
  required_version = ">= 1.11" # 쓰기 전용 인자(password_wo) — DB 비밀번호를 state 에 남기지 않는다

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.7"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = { Project = "caffeine" }
  }
}
