$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'desktop-common.ps1')
. (Join-Path $PSScriptRoot 'operator-common.ps1')
$fixture = Join-Path $env:TEMP ('novel desktop ' + [guid]::NewGuid().ToString('N'))
$registryPath = 'Software\NovelAudio\Tests\' + [guid]::NewGuid().ToString('N')
$key = $null
$passed = 0
function Assert-Desktop($value) { if (-not $value) { throw 'desktop_test_failed' }; $script:passed++ }
try {
    New-Item -ItemType Directory -Path $fixture | Out-Null
    $config = Join-Path $fixture 'local model.json'
    [IO.File]::WriteAllText($config, '{}')
    foreach ($name in 'start-agent.ps1','stop-agent.ps1','desktop.ps1') {
        [IO.File]::WriteAllText((Join-Path $fixture $name), 'param($ConfigPath,$StartupTimeoutSeconds) if (-not (Test-Path -LiteralPath $ConfigPath)) { exit 2 }; exit 0')
    }
    $args = @(Get-DesktopArguments -Action start -ScriptRoot $fixture -ConfigPath $config)
    Assert-Desktop ($args -contains $config)
    Assert-Desktop ($args -contains (Join-Path $fixture 'start-agent.ps1'))
    Assert-Desktop ($args[-2] -eq '-StartupTimeoutSeconds' -and $args[-1] -eq '900')
    $process = Start-DesktopOperator -Action start -ScriptRoot $fixture -ConfigPath $config
    $process.WaitForExit()
    Assert-Desktop ($process.ExitCode -eq 0)
    $stop = @(Get-DesktopArguments -Action stop -ScriptRoot $fixture -ConfigPath $config)
    Assert-Desktop (-not ($stop -contains '-StartupTimeoutSeconds'))
    $command = Get-DesktopAutostartCommand -ScriptRoot $fixture -ConfigPath $config
    Assert-Desktop ($command.Contains('"' + $config + '"') -and $command.Contains('-StartAgent'))
    $key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey($registryPath)
    $key.SetValue('Unrelated', 'preserve')
    Set-DesktopAutostartValue -Key $key -Command $command -Enabled $true
    Assert-Desktop ($key.GetValue('NovelAudioLocal') -eq $command)
    Set-DesktopAutostartValue -Key $key -Command $command -Enabled $false
    Assert-Desktop ($null -eq $key.GetValue('NovelAudioLocal') -and $key.GetValue('Unrelated') -eq 'preserve')
    $key.SetValue('NovelAudioLocal', 'different owner')
    $rejected = $false
    try { Set-DesktopAutostartValue -Key $key -Command $command -Enabled $true } catch { $rejected = $_.Exception.Message -eq 'desktop_autostart_conflict' }
    Assert-Desktop ($rejected -and $key.GetValue('NovelAudioLocal') -eq 'different owner')
    $context = Get-OperatorContext -ConfigPath $config
    Ensure-OperatorDirectory $context.State
    Assert-Desktop ((Get-DesktopAgentState -Context $context) -eq 'stopped')
    Write-SafeStateText -Context $context -Name 'agent.pid' -Content ([string]$PID)
    Assert-Desktop ((Get-DesktopAgentState -Context $context) -eq 'ownership needs check')
    Write-Output ("desktop_tests_passed=" + $passed)
} finally {
    if ($key) { $key.Dispose() }
    [Microsoft.Win32.Registry]::CurrentUser.DeleteSubKeyTree($registryPath, $false)
    # Only this test's verified fixture may be removed.
    if ((Test-Path -LiteralPath $fixture) -and [IO.Path]::GetFullPath($fixture).StartsWith([IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\')) {
        Remove-Item -LiteralPath $fixture -Recurse -Force
    }
}
