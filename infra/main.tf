# Temporary EC2 spot machines that run JMH benchmarks in parallel.
# Each machine runs its share of the plan (base and new version of a benchmark back to back on the
# same hardware), uploads the results to S3 and terminates itself. Driven by infra/bench.sh; see docs/en/benchmarks.md.

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
  region  = var.region
  profile = var.profile
  default_tags {
    tags = {
      Project = "resilience4cata-bench"
      Run     = var.run_id
    }
  }
}

locals {
  machines = { for m in jsondecode(file(var.plan_file)) : m.name => m }
}

# Latest Amazon Linux 2023 for the instance's architecture (Graviton by default).
data "aws_ssm_parameter" "ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-${var.architecture}"
}

# Only the zones that offer the instance type (us-east-1e, for example, has no Graviton).
data "aws_ec2_instance_type_offerings" "zones" {
  location_type = "availability-zone"
  filter {
    name   = "instance-type"
    values = [var.instance_type]
  }
}

locals {
  zones = sort(data.aws_ec2_instance_type_offerings.zones.locations)
}

# A throwaway network for this run (the account may have no default VPC): one public subnet per
# zone, so spot capacity can come from any of them. No inbound rules anywhere.
resource "aws_vpc" "run" {
  cidr_block = "10.42.0.0/16"
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
  count                   = length(local.zones)
  vpc_id                  = aws_vpc.run.id
  availability_zone       = local.zones[count.index]
  cidr_block              = cidrsubnet(aws_vpc.run.cidr_block, 8, count.index)
  map_public_ip_on_launch = true
}

resource "aws_route_table_association" "run" {
  count          = length(aws_subnet.run)
  subnet_id      = aws_subnet.run[count.index].id
  route_table_id = aws_route_table.run.id
}

resource "aws_s3_bucket" "run" {
  bucket_prefix = "r4c-bench-"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "run" {
  bucket                  = aws_s3_bucket.run.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_object" "source" {
  for_each = { base = var.base_bundle, new = var.new_bundle }
  bucket   = aws_s3_bucket.run.id
  key      = "src/${each.key}.tar.gz"
  source   = each.value
  etag     = filemd5(each.value)
}

resource "aws_iam_role" "bench" {
  name_prefix = "r4c-bench-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# The machines may only read the sources and write results in this run's bucket.
resource "aws_iam_role_policy" "bench" {
  role = aws_iam_role.bench.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "${aws_s3_bucket.run.arn}/src/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = "${aws_s3_bucket.run.arn}/results/*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "bench" {
  name_prefix = "r4c-bench-"
  role        = aws_iam_role.bench.name
}

# No inbound access at all: the machines only download dependencies and talk to S3.
resource "aws_security_group" "bench" {
  name_prefix = "r4c-bench-"
  vpc_id      = aws_vpc.run.id
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "bench" {
  for_each = local.machines

  ami                         = data.aws_ssm_parameter.ami.value
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.run[index(keys(local.machines), each.key) % length(aws_subnet.run)].id
  vpc_security_group_ids      = [aws_security_group.bench.id]
  iam_instance_profile        = aws_iam_instance_profile.bench.name
  associate_public_ip_address = true
  # Powering off terminates the machine. Spot one-time instances already behave that way and EC2
  # rejects setting the attribute on them, so it is only set for on-demand machines.
  instance_initiated_shutdown_behavior = index(keys(local.machines), each.key) < var.spot_machines ? null : "terminate"

  # The first spot_machines machines are spot, the rest on-demand (same type, same hardware):
  # accounts can cap the number of spot instances well below their spot vCPU quota.
  dynamic "instance_market_options" {
    for_each = index(keys(local.machines), each.key) < var.spot_machines ? [1] : []
    content {
      market_type = "spot"
      spot_options {
        spot_instance_type             = "one-time"
        instance_interruption_behavior = "terminate"
      }
    }
  }

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    bucket      = aws_s3_bucket.run.id
    name        = each.key
    runs        = join("\n", [for r in each.value.runs : "${r.version}|${r.project}|${r.jdk}|${r.threads}|${r.id}|${r.regex}"])
    jmh_options = var.jmh_options
    max_minutes = var.max_minutes
  })

  tags = {
    Name = "r4c-bench-${each.key}"
  }

  depends_on = [aws_s3_object.source, aws_iam_role_policy.bench, aws_route_table_association.run]
}
