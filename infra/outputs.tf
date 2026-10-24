output "bucket" {
  value = aws_s3_bucket.run.id
}

output "instances" {
  value = { for name, instance in aws_instance.bench : name => instance.id }
}
