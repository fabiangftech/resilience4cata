variable "region" {
  type    = string
  default = "us-east-1"
}

variable "instance_type" {
  description = "c7g.4xlarge: 16 Graviton3 cores without SMT; machine.sh gives 4 to the service, 4 to the backend, 8 to k6."
  type        = string
  default     = "c7g.4xlarge"
}

variable "run_id" {
  type = string
}

variable "bundle" {
  description = "The bundle run.sh builds: the three services, the backend, load.js and machine.sh."
  type        = string
}

variable "max_minutes" {
  description = "Hard limit: the machine powers off (and terminates) after this many minutes whatever happens."
  type        = number
  default     = 60
}
