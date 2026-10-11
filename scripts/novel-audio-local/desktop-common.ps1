# Thin desktop adapter. All process ownership and cleanup stay in the operator.
function Get-DesktopArguments {
    param([ValidateSet('start','stop','tray')][string]$Action,
          [string]$ScriptRoot, [string]$ConfigPath)
    $config = [IO.Path]::GetFullPath($ConfigPath)
    $name = @{ start='start-agent.ps1'; stop='stop-agent.ps1'; tray='desktop.ps1' }[$Action]
    $scriptPath = [IO.Path]::GetFullPath((Join-Path $ScriptRoot $name))
    foreach ($path in $config,$scriptPath) {
        if ($path -match '["\r\n]' -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw 'desktop_invalid_path'
        }
    }
    $args = @('-NoProfile','-NonInteractive','-WindowStyle','Hidden','-ExecutionPolicy','Bypass')
    if ($Action -eq 'tray') { $args += '-STA' }
    $args += @('-File',$scriptPath,'-ConfigPath',$config)
    if ($Action -eq 'start') { $args += @('-StartupTimeoutSeconds','900') }
    if ($Action -eq 'tray') { $args += '-StartAgent' }
    return $args
}

function ConvertTo-DesktopArgumentLine {
    param([string[]]$Arguments)
    # All paths are leaf files, so no trailing directory slash needs doubling.
    if (@($Arguments | Where-Object { $_ -match '["\r\n]' }).Count) { throw 'desktop_invalid_argument' }
    return (($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' ')
}

function Get-DesktopPowerShell { return (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') }

function Start-DesktopOperator {
    param([ValidateSet('start','stop')][string]$Action, [string]$ScriptRoot, [string]$ConfigPath)
    $line = ConvertTo-DesktopArgumentLine @(Get-DesktopArguments -Action $Action -ScriptRoot $ScriptRoot -ConfigPath $ConfigPath)
    return (Start-Process -FilePath (Get-DesktopPowerShell) -ArgumentList $line -WindowStyle Hidden -PassThru)
}

function Get-DesktopAutostartCommand {
    param([string]$ScriptRoot, [string]$ConfigPath)
    # Run values are limited to 260 characters. Validate the same file arguments,
    # then use native PowerShell 5.1 host aliases instead of the verbose launch.
    $arguments = @(Get-DesktopArguments -Action tray -ScriptRoot $ScriptRoot -ConfigPath $ConfigPath)
    $fileIndex = [Array]::IndexOf($arguments, '-File')
    $configIndex = [Array]::IndexOf($arguments, '-ConfigPath')
    $command = '"' + (Get-DesktopPowerShell) + '" -nop -sta -w Hidden -ep Bypass -File "' +
        $arguments[$fileIndex + 1] + '" -ConfigPath "' + $arguments[$configIndex + 1] + '" -StartAgent'
    if ($command.Length -gt 260) { throw 'desktop_autostart_path_too_long' }
    return $command
}

function Set-DesktopAutostartValue {
    param([Microsoft.Win32.RegistryKey]$Key, [string]$Command, [bool]$Enabled)
    $existing = $Key.GetValue('NovelAudioLocal')
    if ($null -ne $existing -and $existing -cne $Command) { throw 'desktop_autostart_conflict' }
    if ($Enabled) { $Key.SetValue('NovelAudioLocal', $Command, [Microsoft.Win32.RegistryValueKind]::String) }
    elseif ($null -ne $existing) { $Key.DeleteValue('NovelAudioLocal', $false) }
}

function Get-DesktopAgentState {
    param($Context)
    $pin = $null
    try {
        $record = Get-AgentRecord -Context $Context
        if ($null -eq $record) { return 'stopped' }
        if ($null -eq $record.Process -or $null -eq $record.Owner -or
            -not (Test-AgentCommand -Process $record.Process -Context $Context)) {
            return 'ownership needs check'
        }
        $pin = New-ProcessPin -Process $record.Process
        if (-not (Test-ProcessPin -Pin $pin) -or
            [int]$record.Owner.pid -ne $pin.ProcessId -or
            [string]$record.Owner.creationDate -ne $pin.CreationDate -or
            [int64]$record.Owner.creationTicks -ne $pin.CreationTicks -or
            [string]$record.Owner.configHash -ne (Get-ConfigHash -Context $Context)) {
            return 'ownership needs check'
        }
        return 'service running'
    } catch { return 'status unavailable' }
    finally { if ($pin) { $pin.Handle.Dispose() } }
}
