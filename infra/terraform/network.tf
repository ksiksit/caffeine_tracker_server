# 네트워크. EC2 는 들어오는 길(인터넷 게이트웨이)이 있는 서브넷에 두고, 나가는 길은 보안 그룹으로 막는다.
# RDS 서브넷에는 인터넷 경로가 없다.

resource "aws_vpc" "main" {
  cidr_block = "10.20.0.0/16"
  # 둘 다 켜야 RDS 주소가 풀리고, 경보의 프라이빗 DNS 엔드포인트를 만들 수 있다
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "caffeine" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "caffeine" }
}

# EC2 유형을 쓸 수 있는 가용영역 (리전마다 일부 영역은 특정 유형이 없다)
data "aws_ec2_instance_type_offerings" "app" {
  location_type = "availability-zone"

  filter {
    name   = "instance-type"
    values = [var.instance_type]
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  app_az   = sort(data.aws_ec2_instance_type_offerings.app.locations)[0]
  other_az = [for az in sort(data.aws_availability_zones.available.names) : az if az != local.app_az][0]
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.20.1.0/24"
  availability_zone = local.app_az

  tags = { Name = "caffeine-public" }

  lifecycle {
    # 영역은 처음 만들 때만 고른다. 나중에 조회 결과가 달라져도 서브넷(과 그 위의 EC2·RDS)을 다시 만들지 않는다
    ignore_changes = [availability_zone]
  }
}

# RDS 서브넷 그룹은 가용영역 두 곳이 필요하다. RDS 자체는 EC2 와 같은 영역(db_a)에 둔다
resource "aws_subnet" "db_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.20.11.0/24"
  availability_zone = local.app_az

  tags = { Name = "caffeine-db-a" }

  lifecycle {
    ignore_changes = [availability_zone]
  }
}

resource "aws_subnet" "db_b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.20.12.0/24"
  availability_zone = local.other_az

  tags = { Name = "caffeine-db-b" }

  lifecycle {
    ignore_changes = [availability_zone]
  }
}

# caffeine-public 만 인터넷 게이트웨이로 보낸다. DB 서브넷은 VPC 기본 라우팅 테이블(VPC 안에서만)을 쓴다
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "caffeine-public" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# --- 보안 그룹. 규칙은 따로 둔 리소스가 전부다 (Terraform 은 새 보안 그룹의 기본 '전체 나가기' 규칙을 지운다) ---

resource "aws_security_group" "ec2" {
  name        = "caffeine-ec2"
  description = "caffeine app EC2 (ssh from admin, api 8080)"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "caffeine-ec2" }
}

resource "aws_security_group" "rds" {
  name        = "caffeine-rds"
  description = "caffeine RDS (3306 from app EC2 only)"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "caffeine-rds" }
}

resource "aws_vpc_security_group_ingress_rule" "ec2_ssh" {
  security_group_id = aws_security_group.ec2.id
  description       = "ssh from admin PC"
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  cidr_ipv4         = var.ssh_cidr
}

resource "aws_vpc_security_group_ingress_rule" "ec2_api" {
  security_group_id = aws_security_group.ec2.id
  description       = "api"
  ip_protocol       = "tcp"
  from_port         = 8080
  to_port           = 8080
  cidr_ipv4         = var.api_cidr
}

# 나가는 규칙은 평소 이것 하나(와 경보 모듈이 더하는 SNS 엔드포인트 443)뿐이다 — 인터넷으로는 나가지 못한다.
# 들어온 SSH·API 의 응답은 규칙 없이도 나간다.
resource "aws_vpc_security_group_egress_rule" "ec2_to_rds" {
  security_group_id            = aws_security_group.ec2.id
  description                  = "mysql to RDS"
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = aws_security_group.rds.id
}

# 첫 부팅의 Docker 설치, OS 패치 때만 연다: terraform apply -var ec2_egress_open=true → 끝나면 terraform apply
resource "aws_vpc_security_group_egress_rule" "ec2_internet" {
  count = var.ec2_egress_open ? 1 : 0

  security_group_id = aws_security_group.ec2.id
  description       = "TEMPORARY https to internet"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_ec2" {
  security_group_id            = aws_security_group.rds.id
  description                  = "mysql from app EC2"
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = aws_security_group.ec2.id
}
