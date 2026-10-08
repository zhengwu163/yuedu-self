param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot "local-model.json"),
    [ValidateRange(1, 60)][int]$TimeoutSeconds = 10
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "operator-common.ps1")

try {
    $context = Get-OperatorContext -ConfigPath $ConfigPath
    Assert-OperatorState $context
    $commandLock = $null
    $cleanupLock = $null
    try {
        $commandLock = Enter-OperatorLock -Context $context
        Assert-OperatorState $context
        $record = Get-AgentRecord -Context $context
        $ownerPath = Join-Path $context.State "agent.owner.json"
        if ($null -eq $record) {
            if (Test-Path -LiteralPath $ownerPath -PathType Leaf) {
                throw "state owner is incomplete"
            }
            Write-Output '{"state":"stopped"}'
            exit 0
        }
        if ($null -eq $record.Process -or $null -eq $record.Owner) {
            throw "unowned pid"
        }
        if (-not (Test-AgentCommand -Process $record.Process -Context $context)) {
            throw "unowned pid"
        }
        $pin = New-ProcessPin -Process $record.Process
        if (
            [int]$record.Owner.pid -ne $pin.ProcessId -or
            [string]$record.Owner.creationDate -ne $pin.CreationDate -or
            [int64]$record.Owner.creationTicks -ne $pin.CreationTicks -or
            [string]$record.Owner.configHash -ne (Get-ConfigHash -Context $context)
        ) {
            throw "unowned pid"
        }
        # First and last identity checks are both required; a recycled PID is never killable.
        Test-ProcessPin -Pin $pin -ThrowOnChange | Out-Null
        $owned = @{}
        $owned[[string]$pin.ProcessId] = $pin
        Add-OwnedChildren -ParentPin $pin -Owned $owned
        Touch-SafeStateFile -Context $context -Name "agent.stop"
        Assert-StateOwner -Context $context -AgentPid $pin.ProcessId -Pin $pin
        Stop-OwnedRecords `
            -Owned $owned `
            -Context $context `
            -TimeoutSeconds $TimeoutSeconds
        $cleanupLock = Enter-StateLock -Context $context -Offset 0
        Assert-StateOwner -Context $context -AgentPid $pin.ProcessId -Pin $pin
        if (Test-ProcessPin -Pin $pin) {
            throw "state owner still running"
        }
        Remove-SafeStateFile -Context $context -Name "agent.pid"
        Remove-SafeStateFile -Context $context -Name "agent.owner.json"
        Remove-SafeStateFile -Context $context -Name "agent.stop"
        Write-SafeStateText `
            -Context $context `
            -Name "agent.status.json" `
            -Content '{"state":"stopped","activeLease":false}' `
            -Encoding ([System.Text.Encoding]::ASCII)
        Write-Output '{"state":"stopped"}'
    } finally {
        Exit-OperatorLock $cleanupLock
        Exit-OperatorLock $commandLock
    }
} catch {
    Write-Output '{"state":"failed","errorCode":"worker_stop_failed"}'
    exit 2
}
