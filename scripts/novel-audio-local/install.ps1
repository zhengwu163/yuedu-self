param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot "local-model.json")
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "operator-common.ps1")

try {
    $context = Get-OperatorContext -ConfigPath $ConfigPath -AllowMissingConfig
    Ensure-OperatorDirectory -Path $context.Root

    $lock = $null
    try {
        # Offset 1 serializes operator commands without taking Python's byte 0.
        $lock = Enter-OperatorLock -Context $context
        Assert-OperatorState $context
        foreach ($directory in @(
            "config",
            "state",
            "logs",
            "diagnostics",
            "manifests",
            "runtime",
            "voices"
        )) {
            Ensure-OperatorDirectory -Path (Join-Path $context.Root $directory)
        }
        Assert-OperatorState $context
        Initialize-OperatorTemplates -Context $context
        Assert-OperatorState $context

        $python = Get-ConfiguredPython -Context $context
        & $python $context.ScriptPath --init --config $context.ConfigPath | Out-Null
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
        Assert-OperatorState $context
        Write-Output "local model service directories initialized"
    } finally {
        Exit-OperatorLock $lock
    }
} catch {
    Write-Output '{"state":"failed","errorCode":"invalid_config"}'
    exit 2
}
