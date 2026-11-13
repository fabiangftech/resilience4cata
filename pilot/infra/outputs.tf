output "bucket" {
  value = aws_s3_bucket.run.id
}

output "instance" {
  value = aws_instance.pilot.id
}
