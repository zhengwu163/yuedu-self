$ErrorActionPreference = "Stop"
$ServiceDir = Split-Path -Parent $MyInvocation.MyCommand.Path
python (Join-Path $ServiceDir "server.py") @args
