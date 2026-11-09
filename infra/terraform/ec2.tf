# 앱 서버. Amazon Linux 2023 x86_64, 고정 IP, 첫 부팅 때 Docker 설치(user-data.sh).

data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_key_pair" "app" {
  key_name   = "caffeine-key"
  public_key = var.ssh_public_key
}

# 인스턴스 역할. 권한은 비어 있고, 경보 모듈이 SNS 발행 정책(caffeine-sns-publish)을 붙인다
resource "aws_iam_role" "ec2" {
  name        = "caffeine-ec2"
  description = "caffeine app EC2"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_instance_profile" "ec2" {
  name = "caffeine-ec2"
  role = aws_iam_role.ec2.name
}

# 인터페이스와 고정 IP 를 먼저 만들어 EC2 가 첫 부팅부터 같은 공인 주소를 쓰게 한다
# (부팅 뒤에 IP 를 바꿔 붙이면 설치 중이던 내려받기가 끊긴다)
resource "aws_network_interface" "app" {
  subnet_id       = aws_subnet.public.id
  security_groups = [aws_security_group.ec2.id]
  description     = "caffeine app EC2"

  tags = { Name = "caffeine-app" }
}

resource "aws_eip" "app" {
  domain            = "vpc"
  network_interface = aws_network_interface.app.id

  tags = { Name = "caffeine-app" }

  depends_on = [aws_internet_gateway.main]
}

resource "aws_instance" "app" {
  ami                  = nonsensitive(data.aws_ssm_parameter.al2023.value)
  instance_type        = var.instance_type
  key_name             = aws_key_pair.app.key_name
  iam_instance_profile = aws_iam_instance_profile.ec2.name
  user_data            = file("${path.module}/user-data.sh")

  primary_network_interface {
    network_interface_id = aws_network_interface.app.id
  }

  # 이미지 네 개 + 배포 tar + Prometheus 2GB + 로그
  root_block_device {
    volume_size = 20
    volume_type = "gp3"
    encrypted   = true

    tags = { Name = "caffeine-app" }
  }

  # 홉 제한 2: 컨테이너(Alertmanager)가 인스턴스 역할 자격 증명을 받으려면 필요하다
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  tags = { Name = "caffeine-app" }

  lifecycle {
    # 새 AMI 가 나오거나 user-data.sh 를 고쳐도 서버를 다시 만들지 않는다 (둘 다 첫 부팅에만 의미가 있다).
    # 다시 만들려면: terraform apply -replace=aws_instance.app
    ignore_changes = [ami, user_data]
  }

  # 첫 부팅 설치가 인터넷에 닿으려면 고정 IP·라우팅·(열었다면) 나가는 규칙이 먼저 있어야 한다
  depends_on = [
    aws_eip.app,
    aws_route_table_association.public,
    aws_vpc_security_group_egress_rule.ec2_internet,
  ]
}
