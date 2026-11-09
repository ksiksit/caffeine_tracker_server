# EC2(인터넷 없음) → SNS 경로 (docs/alerting-plan.md §7). 인터넷으로 나가는 길은 열지 않는다.

resource "aws_security_group" "sns_endpoint" {
  name        = "caffeine-sns-endpoint"
  description = "SNS interface endpoint for caffeine alerts (443 from app EC2 only)"
  vpc_id      = var.vpc_id

  tags = { Name = "caffeine-sns-endpoint" }
}

resource "aws_vpc_security_group_ingress_rule" "endpoint_from_ec2" {
  security_group_id            = aws_security_group.sns_endpoint.id
  description                  = "https from app EC2"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = var.ec2_security_group_id
}

# 대상이 엔드포인트 보안 그룹뿐이라 인터넷은 그대로 막힌다
resource "aws_vpc_security_group_egress_rule" "ec2_to_endpoint" {
  security_group_id            = var.ec2_security_group_id
  description                  = "https to SNS endpoint"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = aws_security_group.sns_endpoint.id
}

# 프라이빗 DNS 를 켜서 기본 SNS 주소(sns.<리전>.amazonaws.com)가 VPC 안에서 사설 IP 로 풀리게 한다
resource "aws_vpc_endpoint" "sns" {
  vpc_id              = var.vpc_id
  service_name        = "com.amazonaws.${var.region}.sns"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = [var.subnet_id]
  security_group_ids  = [aws_security_group.sns_endpoint.id]
  private_dns_enabled = true

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PublishAlertsOnly"
      Effect    = "Allow"
      Principal = "*"
      Action    = "sns:Publish"
      Resource  = local.topic_arns
    }]
  })

  tags = { Name = "caffeine-sns" }
}

# 키를 .env 나 레포에 두지 않는다 — Alertmanager 가 인스턴스 역할로 발행한다
resource "aws_iam_role_policy" "ec2_sns_publish" {
  name = "caffeine-sns-publish"
  role = var.ec2_role_name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "PublishAlertsOnly"
      Effect   = "Allow"
      Action   = "sns:Publish"
      Resource = local.topic_arns
    }]
  })
}
