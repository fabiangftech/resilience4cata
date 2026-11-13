# One temporary EC2 machine for the pilot (pilot/run.sh): it downloads the bundle, runs pilot/machine.sh, uploads the
# results to S3 and terminates itself. No inbound access. Every resource is destroyed by run.sh afterwards.

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Project = "resilience4cata-pilot"
      Run     = var.run_id
    }
  }
}

data "aws_ssm_parameter" "ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

data "aws_ec2_instance_type_offerings" "zones" {
  location_type = "availability-zone"
  filter {
    name   = "instance-type"
    values = [var.instance_type]
  }
}

resource "aws_vpc" "run" {
  cidr_block = "10.43.0.0/16"
}

resource "aws_internet_gateway" "run" {
  vpc_id = aws_vpc.run.id
}

resource "aws_route_table" "run" {
  vpc_id = aws_vpc.run.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.run.id
  }
}

resource "aws_subnet" "run" {
  vpc_id                  = aws_vpc.run.id
  availability_zone       = sort(data.aws_ec2_instance_type_offerings.zones.locations)[0]
  cidr_block              = cidrsubnet(aws_vpc.run.cidr_block, 8, 0)
  map_public_ip_on_launch = true
}

resource "aws_route_table_association" "run" {
  subnet_id      = aws_subnet.run.id
  route_table_id = aws_route_table.run.id
}

resource "aws_s3_bucket" "run" {
  bucket_prefix = "r4c-pilot-"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "run" {
  bucket                  = aws_s3_bucket.run.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_object" "bundle" {
  bucket = aws_s3_bucket.run.id
  key    = "bundle.tar.gz"
  source = var.bundle
  etag   = filemd5(var.bundle)
}

resource "aws_iam_role" "pilot" {
  name_prefix = "r4c-pilot-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# The machine may only read the bundle and write results in this run's bucket.
resource "aws_iam_role_policy" "pilot" {
  role = aws_iam_role.pilot.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:GetObject"], Resource = "${aws_s3_bucket.run.arn}/bundle.tar.gz" },
      { Effect = "Allow", Action = ["s3:PutObject"], Resource = "${aws_s3_bucket.run.arn}/results/*" },
    ]
  })
}

resource "aws_iam_instance_profile" "pilot" {
  name_prefix = "r4c-pilot-"
  role        = aws_iam_role.pilot.name
}

resource "aws_security_group" "pilot" {
  name_prefix = "r4c-pilot-"
  vpc_id      = aws_vpc.run.id
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "pilot" {
  ami                                  = data.aws_ssm_parameter.ami.value
  instance_type                        = var.instance_type
  subnet_id                            = aws_subnet.run.id
  vpc_security_group_ids               = [aws_security_group.pilot.id]
  iam_instance_profile                 = aws_iam_instance_profile.pilot.name
  associate_public_ip_address          = true
  instance_initiated_shutdown_behavior = "terminate"

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    bucket      = aws_s3_bucket.run.id
    max_minutes = var.max_minutes
  })

  tags = {
    Name = "r4c-pilot-${var.run_id}"
  }

  depends_on = [aws_s3_object.bundle, aws_iam_role_policy.pilot, aws_route_table_association.run]
}
