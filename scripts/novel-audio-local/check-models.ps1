param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot "local-model.json")
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "operator-common.ps1")

try {
    $context = Get-OperatorContext -ConfigPath $ConfigPath
    Assert-OperatorState $context
    $code = Invoke-OperatorCheck -Context $context
    Assert-OperatorState $context
    exit $code
} catch {
    Write-Output '{"overall":"FAIL","exitCode":2,"checks":{"config":{"status":"FAIL","code":"invalid_config"}}}'
    exit 2
}
