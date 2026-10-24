variable "profile" {
  description = "AWS CLI profile; null uses the standard environment variables (bench.sh loads them from .env)."
  type        = string
  default     = null
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "instance_type" {
  description = "Compute-optimized, non-burstable. c7g.xlarge: 4 Graviton cores without SMT or turbo, so -t 4 gets 4 real cores at a fixed clock."
  type        = string
  default     = "c7g.xlarge"
}

variable "architecture" {
  description = "arm64 for Graviton (c7g), x86_64 for Intel/AMD (c7i, c7a)."
  type        = string
  default     = "arm64"
}

variable "run_id" {
  description = "Identifies this run in tags and in the results directory."
  type        = string
}

variable "base_bundle" {
  description = "Path to the base benchmark bundle (.tar.gz with classpath.txt and cp/), built by plan.py bundle."
  type        = string
}

variable "new_bundle" {
  description = "Path to the new benchmark bundle."
  type        = string
}

variable "spot_machines" {
  description = "How many machines are spot instances; the rest are on-demand. Limited by the account's spot instance count."
  type        = number
  default     = 3
}

variable "plan_file" {
  description = "JSON plan from plan.py: one entry per machine with its ordered list of JMH runs."
  type        = string
}

variable "jmh_options" {
  description = "Extra JMH options for every run."
  type        = string
  default     = "-prof gc"
}

variable "max_minutes" {
  description = "Hard limit: a machine powers off (and terminates) after this many minutes whatever happens."
  type        = number
  default     = 45
}
