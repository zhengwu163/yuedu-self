param([Parameter(Mandatory=$true)][string]$ConfigPath, [switch]$StartAgent)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'desktop-common.ps1')
. (Join-Path $PSScriptRoot 'operator-common.ps1')
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$context = Get-OperatorContext -ConfigPath $ConfigPath
$digest = [Security.Cryptography.SHA256]::Create()
$id = -join ($digest.ComputeHash([Text.Encoding]::UTF8.GetBytes($context.ConfigPath.ToLowerInvariant())) | ForEach-Object { $_.ToString('x2') })
$digest.Dispose()
$mutex = New-Object Threading.Mutex($false, ('Local\NovelAudioDesktop-' + $id))
$ownsMutex = $false
$notify = $null
$timer = $null
$script:commandProcess = $null
try {
    try { $ownsMutex = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { exit 0 }
    $notify = New-Object Windows.Forms.NotifyIcon
    $notify.Icon = [Drawing.SystemIcons]::Application
    $notify.Text = 'NovelAudio'
    $menu = New-Object Windows.Forms.ContextMenuStrip
    $statusItem = $menu.Items.Add('NovelAudio - checking')
    $statusItem.Enabled = $false
    $startItem = $menu.Items.Add('Start service')
    $stopItem = $menu.Items.Add('Stop service')
    $menu.Items.Add('-') | Out-Null
    $exitItem = $menu.Items.Add('Exit tray (service stays running)')
    $notify.ContextMenuStrip = $menu
    $notify.Visible = $true
    $script:desktopRoot = $PSScriptRoot
    $script:desktopConfig = $context.ConfigPath
    $startItem.add_Click({
        try { $script:commandProcess = Start-DesktopOperator -Action start -ScriptRoot $script:desktopRoot -ConfigPath $script:desktopConfig }
        catch { $statusItem.Text = 'NovelAudio - command failed' }
    })
    $stopItem.add_Click({
        try { $script:commandProcess = Start-DesktopOperator -Action stop -ScriptRoot $script:desktopRoot -ConfigPath $script:desktopConfig }
        catch { $statusItem.Text = 'NovelAudio - command failed' }
    })
    $exitItem.add_Click({ [Windows.Forms.Application]::ExitThread() })
    $timer = New-Object Windows.Forms.Timer
    $timer.Interval = 2000
    $timer.add_Tick({
        $busy = $script:commandProcess -and -not $script:commandProcess.HasExited
        $startItem.Enabled = -not $busy
        $stopItem.Enabled = -not $busy
        if ($busy) { $statusItem.Text = 'NovelAudio - command running'; return }
        $statusItem.Text = 'NovelAudio - ' + (Get-DesktopAgentState -Context $context)
    })
    if ($StartAgent) { $script:commandProcess = Start-DesktopOperator -Action start -ScriptRoot $PSScriptRoot -ConfigPath $context.ConfigPath }
    $timer.Start()
    [Windows.Forms.Application]::Run()
} finally {
    if ($timer) { $timer.Stop(); $timer.Dispose() }
    if ($notify) { $notify.Visible = $false; $notify.Dispose() }
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
