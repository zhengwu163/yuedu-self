param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot "local-model.json"),
    [ValidateRange(1, 1800)][int]$StartupTimeoutSeconds = 900
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "operator-common.ps1")

function Write-StartFailure {
    Write-Output '{"state":"failed","errorCode":"agent_start_failed"}'
    exit 2
}

try {
    $context = Get-OperatorContext -ConfigPath $ConfigPath
    Assert-OperatorState $context
    $commandLock = $null
    $reservationLock = $null
    $process = $null
    $pin = $null
    $logGuard = $null
    $stateWriteAttempted = $false
    try {
        # Byte 1 serializes starts/stops. Byte 0 is reserved for the Python agent.
        $commandLock = Enter-OperatorLock -Context $context
        Assert-OperatorState $context

        $record = Get-AgentRecord -Context $context
        if ($null -ne $record -and $null -eq $record.Process) {
            if (Recover-ExitedAgentState -Context $context -Record $record) {
                $record = $null
            }
        }
        if ($null -ne $record) {
            if ($null -eq $record.Process -or $null -eq $record.Owner) {
                throw "existing agent ownership cannot be proved"
            }
            $existingPin = New-ProcessPin -Process $record.Process
            if (
                $record.Process.CommandLine -and
                (Test-AgentCommand -Process $record.Process -Context $context) -and
                [int]$record.Owner.pid -eq $existingPin.ProcessId -and
                [string]$record.Owner.creationDate -eq $existingPin.CreationDate -and
                [int64]$record.Owner.creationTicks -eq $existingPin.CreationTicks -and
                [string]$record.Owner.configHash -eq (Get-ConfigHash -Context $context)
            ) {
                if (Test-AgentHealth -Context $context -Pin $existingPin) {
                    @{ state = "started"; pid = $existingPin.ProcessId } |
                        ConvertTo-Json -Compress
                    exit 0
                }
                if (Wait-ForHealthyAgent `
                    -Context $context `
                    -Pin $existingPin `
                    -Deadline ((Get-Date).AddSeconds($StartupTimeoutSeconds))) {
                    @{ state = "started"; pid = $existingPin.ProcessId } |
                        ConvertTo-Json -Compress
                    exit 0
                }
                throw "existing agent is not healthy"
            }
            throw "existing pid is not an owned agent"
        }

        # A missing PID is only launchable after the entire state boundary is checked.
        Assert-OperatorState $context
        $checkCode = Invoke-OperatorCheck -Context $context
        if ($checkCode -ne 0) {
            exit $checkCode
        }
        Assert-OperatorState $context

        $logs = Join-Path $context.Root "logs"
        Ensure-OperatorDirectory -Path $logs
        $stdoutLog = Join-Path $logs "agent.log"
        $stderrLog = Join-Path $logs "agent-error.log"
        Assert-NoReparsePath -Path $stdoutLog -AllowMissing
        Assert-NoReparsePath -Path $stderrLog -AllowMissing
        $logGuard = [NovelAudio.OperatorNative]::PinDirectory($logs, $false)
        $python = Get-ConfiguredPython -Context $context
        Assert-NoReparsePath -Path $python

        $reservationLock = Enter-StateLock -Context $context -Offset 0
        Assert-OperatorState $context
        # Python's StateLock is non-blocking and process-held. Release the
        # reservation before CreateProcess so server.py can acquire byte 0.
        Exit-OperatorLock $reservationLock
        $reservationLock = $null
        $arguments = @(
            ('"' + $context.ScriptPath + '"'),
            "--serve",
            "--config",
            ('"' + $context.ConfigPath + '"')
        )
        $process = Start-Process `
            -FilePath $python `
            -ArgumentList $arguments `
            -WorkingDirectory $context.Root `
            -RedirectStandardOutput $stdoutLog `
            -RedirectStandardError $stderrLog `
            -WindowStyle Hidden `
            -PassThru
        if ($null -eq $process -or $process.Id -le 0) {
            throw "startup failed"
        }
        $process.Refresh()
        if ($process.HasExited) {
            throw "startup failed"
        }
        Assert-NoReparsePath -Path $stdoutLog
        Assert-NoReparsePath -Path $stderrLog
        $processInfo = Get-CimInstance Win32_Process `
            -Filter "ProcessId = $($process.Id)" `
            -ErrorAction Stop | Select-Object -First 1
        if ($null -eq $processInfo) {
            throw "startup process disappeared"
        }
        if (-not (Test-AgentCommand -Process $processInfo -Context $context)) {
            throw "started process command is not owned"
        }
        $pin = New-ProcessPin -Process $processInfo
        $agentPin = Resolve-AgentLaunchPin -Context $context -LaunchPin $pin `
            -Deadline ((Get-Date).AddSeconds($StartupTimeoutSeconds))
        if ($agentPin.ProcessId -ne $pin.ProcessId) {
            $pin.Handle.Dispose()
            $pin = $agentPin
        }
        $stateWriteAttempted = $true
        Save-AgentOwner -Context $context -Pin $pin
        Write-SafeStateText `
            -Context $context `
            -Name "agent.pid" `
            -Content ([string]$pin.ProcessId + "`n") `
            -Encoding ([System.Text.Encoding]::ASCII)
        Assert-StateOwner -Context $context -AgentPid $pin.ProcessId -Pin $pin

        if (Wait-ForHealthyAgent `
            -Context $context `
            -Pin $pin `
            -Deadline ((Get-Date).AddSeconds($StartupTimeoutSeconds))) {
            @{ state = "started"; pid = $pin.ProcessId } |
                ConvertTo-Json -Compress
            exit 0
        }
        throw "startup timeout"
    } catch {
        if ($null -ne $pin -and $stateWriteAttempted) {
            try {
                if (Test-ProcessPin $pin) {
                    $owned = @{}
                    $owned[[string]$pin.ProcessId] = $pin
                    Add-OwnedChildren -ParentPin $pin -Owned $owned
                    Stop-OwnedRecords `
                        -Owned $owned `
                        -Context $context `
                        -TimeoutSeconds 5
                }
                $cleanupLock = Enter-StateLock -Context $context -Offset 0
                try {
                    Assert-StateOwner -Context $context -AgentPid $pin.ProcessId -Pin $pin
                    Clear-StaleAgentState -Context $context
                } finally {
                    Exit-OperatorLock $cleanupLock
                }
            } catch {
                # Never replace state owned by a process that appeared after this start.
            }
        } elseif ($null -ne $process) {
            $process.Refresh()
            if (-not $process.HasExited) {
                $processInfo = Get-CimInstance Win32_Process `
                    -Filter "ProcessId = $($process.Id)" `
                    -ErrorAction SilentlyContinue | Select-Object -First 1
                if ($null -ne $processInfo) {
                    $candidate = New-ProcessPin -Process $processInfo
                    if (Test-AgentCommand -Process $processInfo -Context $context) {
                        Invoke-AgentTermination -AgentPid $candidate.ProcessId
                    }
                }
            }
        }
        throw
    } finally {
        if ($null -ne $logGuard) {
            $logGuard.Dispose()
        }
        Exit-OperatorLock $reservationLock
        Exit-OperatorLock $commandLock
    }
} catch {
    Write-StartFailure
}
