param(
    [switch]$KeepFixture
)

$ErrorActionPreference = "Stop"
if ($env:OS -ne "Windows_NT") {
    Write-Output "SKIP: native Windows PowerShell 5.1 is required"
    exit 4
}

. (Join-Path $PSScriptRoot "operator-common.ps1")

$script:passed = 0
$script:skipped = 0
$script:fixture = Join-Path ([System.IO.Path]::GetTempPath()) `
    ("novel-audio-operator-" + [guid]::NewGuid().ToString("N"))
$configPath = Join-Path $script:fixture "local-model.json"

function Assert-OperatorTest {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Condition) {
        throw "FAIL: $Message"
    }
}

function Invoke-Case {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][scriptblock]$Body
    )

    try {
        & $Body
        $script:passed++
        Write-Output ("PASS: " + $Name)
    } catch {
        throw ("FAIL: " + $Name + ": " + $_.Exception.Message)
    }
}

try {
    New-Item -ItemType Directory -Path $script:fixture -Force | Out-Null
    Set-Content -LiteralPath $configPath `
        -Value '{"tts":{"pythonExecutable":"C:\\Python\\python.exe"}}' `
        -Encoding ASCII
    $context = Get-OperatorContext -ConfigPath $configPath
    Ensure-OperatorDirectory $context.State

    Invoke-Case "exact process command rejects server.py.bak" {
        $bad = [pscustomobject]@{
            ExecutablePath = 'C:\Python\python.exe'
            CommandLine = ('"C:\Python\python.exe" "{0}.bak" --serve --config "{1}"' -f
                $context.ScriptPath, $context.ConfigPath)
        }
        Assert-OperatorTest `
            (-not (Test-AgentCommand -Process $bad -Context $context)) `
            "server.py.bak was accepted as the service script"
    }

    Invoke-Case "exact process command accepts script serve config tuple" {
        $good = [pscustomobject]@{
            ExecutablePath = 'C:\Python\python.exe'
            CommandLine = ('"C:\Python\python.exe" "{0}" --serve --config "{1}"' -f
                $context.ScriptPath, $context.ConfigPath)
        }
        Assert-OperatorTest `
            (Test-AgentCommand -Process $good -Context $context) `
            "the exact service command was rejected"
    }

    Invoke-Case "operator lock serializes concurrent lifecycle commands" {
        $first = Enter-OperatorLock -Context $context
        try {
            $busy = $false
            try {
                $second = Enter-OperatorLock -Context $context -TimeoutSeconds 0
                Exit-OperatorLock $second
            } catch {
                $busy = $true
            }
            Assert-OperatorTest $busy "a second operator lock was admitted"
        } finally {
            Exit-OperatorLock $first
        }
    }

    Invoke-Case "creation identity is part of the process pin" {
        $process = Get-CimInstance Win32_Process `
            -Filter "ProcessId = $PID" | Select-Object -First 1
        $pin = New-ProcessPin $process
        Assert-OperatorTest ($pin.ProcessId -eq $PID) "wrong PID in process pin"
        Assert-OperatorTest ($pin.CreationTicks -gt 0) "missing native CreationDate pin"
        Assert-OperatorTest (Test-ProcessPin $pin) "fresh process pin did not revalidate"
    }

    Invoke-Case "new owner cannot be removed by an old stop operation" {
        $pin = [pscustomobject]@{
            ProcessId = 4242
            CreationDate = "old"
            CreationTicks = [int64]1
        }
        $pidPath = Join-Path $context.State "agent.pid"
        Save-AgentOwner -Context $context -Pin $pin
        Write-SafeStateText -Context $context -Name "agent.pid" `
            -Content "4242`n" -Encoding ([System.Text.Encoding]::ASCII)
        Write-SafeStateText -Context $context -Name "agent.pid" `
            -Content "4343`n" -Encoding ([System.Text.Encoding]::ASCII)
        $rejected = $false
        try {
            Assert-StateOwner -Context $context -AgentPid 4242 -Pin $pin
        } catch {
            $rejected = $true
        }
        Assert-OperatorTest $rejected "old owner was allowed to clean new state"
    }

    Invoke-Case "termination mock receives only proven owned PID" {
        $script:terminationCalls = @()
        $script:alive = $true
        $script:mockPin = [pscustomobject]@{
            ProcessId = 4242
            CreationDate = "mock"
            CreationTicks = [int64]1
        }
        function global:Add-OwnedChildren {
            param($ParentPin, $Owned)
        }
        function global:Get-AliveOwnedRecords {
            param($Owned)
            if ($script:alive) {
                return @($script:mockPin)
            }
            return @()
        }
        function global:Invoke-AgentTermination {
            param([int]$AgentPid)
            $script:terminationCalls += $AgentPid
            $script:alive = $false
        }
        $owned = @{ "4242" = $script:mockPin }
        Stop-OwnedRecords -Owned $owned -Context $context -TimeoutSeconds 1
        Assert-OperatorTest `
            ($script:terminationCalls.Count -eq 1 -and
             $script:terminationCalls[0] -eq 4242) `
            "termination boundary targeted an unexpected PID"
        Remove-Item function:\Add-OwnedChildren -Force
        Remove-Item function:\Get-AliveOwnedRecords -Force
        Remove-Item function:\Invoke-AgentTermination -Force
    }

    Invoke-Case "reparse-point state is rejected before writes" {
        $outside = Join-Path $script:fixture "outside"
        $linkedState = Join-Path $script:fixture "linked-state"
        New-Item -ItemType Directory -Path $outside -Force | Out-Null
        try {
            New-Item -ItemType Junction -Path $linkedState -Target $outside -ErrorAction Stop |
                Out-Null
        } catch {
            $script:skipped++
            Write-Output "SKIP: Junction creation requires Windows filesystem support"
            return
        }
        $linkedContext = [pscustomobject]@{
            Root = $script:fixture
            State = $linkedState
            ConfigPath = $configPath
            ScriptPath = $context.ScriptPath
        }
        $rejected = $false
        try {
            Assert-OperatorState $linkedContext
        } catch {
            $rejected = $true
        }
        Assert-OperatorTest $rejected "junction state directory was accepted"
    }

    Invoke-Case "symbolic-link state file is rejected before writes" {
        $outsideFile = Join-Path $script:fixture "symbolic-outside.txt"
        $linkedFile = Join-Path $context.State "agent.stop"
        Set-Content -LiteralPath $outsideFile -Value "preserve" -Encoding ASCII
        try {
            New-Item -ItemType SymbolicLink -Path $linkedFile -Target $outsideFile `
                -ErrorAction Stop | Out-Null
        } catch {
            $script:skipped++
            Write-Output "SKIP: SymbolicLink creation requires link privilege"
            return
        }
        $rejected = $false
        try {
            Assert-NoReparsePath -Path $linkedFile
        } catch {
            $rejected = $true
        }
        Assert-OperatorTest $rejected "SymbolicLink state file was accepted"
        Assert-OperatorTest `
            ((Get-Content -LiteralPath $outsideFile -Raw).Trim() -eq "preserve") `
            "SymbolicLink target was modified"
    }

    Invoke-Case "unhealthy agent is not accepted" {
        $fakePin = [pscustomobject]@{
            ProcessId = 4242
            CreationDate = "unhealthy"
            CreationTicks = [int64]1
        }
        Assert-OperatorTest `
            (-not (Test-AgentHealth -Context $context -Pin $fakePin -TimeoutMilliseconds 50)) `
            "unhealthy agent was accepted"
    }

    Invoke-Case "health checks reject redirecting or proxied responses" {
        $commonPath = Join-Path $PSScriptRoot "operator-common.ps1"
        $commonText = Get-Content -LiteralPath $commonPath -Raw
        Assert-OperatorTest `
            ($commonText.Contains("AllowAutoRedirect = `$false")) `
            "health checks allow redirects"
        Assert-OperatorTest `
            ($commonText.Contains("Proxy = `$null")) `
            "health checks allow proxy interception"
    }

    Write-Output ("SUMMARY: {0} passed, {1} skipped" -f $script:passed, $script:skipped)
    exit 0
} catch {
    Write-Output $_.Exception.Message
    exit 1
} finally {
    if (-not $KeepFixture -and (Test-Path -LiteralPath $script:fixture)) {
        Remove-Item -LiteralPath $script:fixture -Recurse -Force
    }
}
