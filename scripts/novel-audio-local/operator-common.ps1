# Shared Windows PowerShell 5.1 operator boundaries.

if (-not ("NovelAudio.OperatorNative" -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace NovelAudio {
    public static class OperatorNative {
        private const uint FILE_READ_ATTRIBUTES = 0x0080;
        private const uint FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
        private const uint FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
        private const uint OPEN_EXISTING = 3;
        private const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
        private const uint INVALID_FILE_ATTRIBUTES = 0xffffffff;

        [StructLayout(LayoutKind.Sequential)]
        private struct FileInfo {
            public uint Attributes;
            public System.Runtime.InteropServices.ComTypes.FILETIME Creation, Access, Write;
            public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
        }
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetFileInformationByHandle(IntPtr handle, out FileInfo info);

        private static void CheckFile(IntPtr handle, bool directory) {
            FileInfo info;
            if (!GetFileInformationByHandle(handle, out info) ||
                (info.Attributes & 0x400) != 0 ||
                ((info.Attributes & 0x10) != 0) != directory ||
                (!directory && info.Links != 1)) {
                throw new IOException("unsafe file");
            }
        }

        // No delete sharing: keep every directory component pinned until the
        // operation finishes. Opening the final component itself avoids following links.
        public static IDisposable PinDirectory(string path, bool create) {
            var pins = new DirectoryPins();
            try {
                var parts = new Stack<string>();
                var current = new DirectoryInfo(Path.GetFullPath(path));
                while (current != null) { parts.Push(current.FullName); current = current.Parent; }
                while (parts.Count > 0) {
                    string part = parts.Pop();
                    if (create && !Directory.Exists(part) && !IsReparsePoint(part)) {
                        Directory.CreateDirectory(part);
                    }
                    IntPtr handle = CreateFile(part, FILE_READ_ATTRIBUTES, FileShare.ReadWrite,
                        IntPtr.Zero, OPEN_EXISTING,
                        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, IntPtr.Zero);
                    if (handle == new IntPtr(-1)) throw new IOException("directory unavailable");
                    pins.Handles.Add(handle);
                    CheckFile(handle, true);
                }
                return pins;
            } catch { pins.Dispose(); throw; }
        }
        private sealed class DirectoryPins : IDisposable {
            public readonly List<IntPtr> Handles = new List<IntPtr>();
            public void Dispose() {
                for (int i = Handles.Count - 1; i >= 0; i--) CloseHandle(Handles[i]);
                Handles.Clear();
            }
        }

        public static FileStream OpenSafeFile(string path, bool write, bool exclusiveCreate) {
            // OPEN_ALWAYS does not truncate: inspect the handle before any write.
            uint mode = exclusiveCreate ? 1u : (write ? 4u : OPEN_EXISTING);
            IntPtr handle = CreateFile(path, write ? 0xc0000000u : 0x80000000u,
                FileShare.ReadWrite, IntPtr.Zero, mode, FILE_FLAG_OPEN_REPARSE_POINT, IntPtr.Zero);
            if (handle == new IntPtr(-1)) throw new IOException("file unavailable");
            var safe = new SafeFileHandle(handle, true);
            try {
                CheckFile(handle, false);
                return new FileStream(safe, write ? FileAccess.ReadWrite : FileAccess.Read);
            } catch { safe.Dispose(); throw; }
        }

        public static SafeProcessHandle PinProcess(int processId) {
            IntPtr process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, (uint)processId);
            if (process == IntPtr.Zero) throw new IOException("process unavailable");
            return new SafeProcessHandle(process, true);
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateFile(
            string name,
            uint access,
            FileShare share,
            IntPtr security,
            uint creation,
            uint flags,
            IntPtr templateFile);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr handle);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFileAttributes(string name);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetProcessTimes(
            IntPtr process,
            out System.Runtime.InteropServices.ComTypes.FILETIME creation,
            out System.Runtime.InteropServices.ComTypes.FILETIME exit,
            out System.Runtime.InteropServices.ComTypes.FILETIME kernel,
            out System.Runtime.InteropServices.ComTypes.FILETIME user);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr OpenProcess(
            uint access, bool inheritHandle, uint processId);

        [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr CommandLineToArgvW(
            string commandLine, out int argc);

        [DllImport("kernel32.dll")]
        private static extern IntPtr LocalFree(IntPtr memory);

        public static bool IsReparsePoint(string path) {
            uint attributes = GetFileAttributes(path);
            if (attributes == INVALID_FILE_ATTRIBUTES) {
                return false;
            }
            return (attributes & 0x400) != 0;
        }

        public static void AssertNoReparsePoint(string path) {
            IntPtr handle = CreateFile(
                path,
                FILE_READ_ATTRIBUTES,
                FileShare.ReadWrite | FileShare.Delete,
                IntPtr.Zero,
                OPEN_EXISTING,
                FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS,
                IntPtr.Zero);
            if (handle == new IntPtr(-1)) {
                if (IsReparsePoint(path)) {
                    throw new IOException("reparse point");
                }
                return;
            }
            try {
                if (IsReparsePoint(path)) {
                    throw new IOException("reparse point");
                }
            } finally {
                CloseHandle(handle);
            }
        }

        public static long GetProcessCreationTicks(int processId) {
            IntPtr process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, (uint)processId);
            if (process == IntPtr.Zero) {
                return 0;
            }
            try {
                System.Runtime.InteropServices.ComTypes.FILETIME creation;
                System.Runtime.InteropServices.ComTypes.FILETIME exit;
                System.Runtime.InteropServices.ComTypes.FILETIME kernel;
                System.Runtime.InteropServices.ComTypes.FILETIME user;
                if (!GetProcessTimes(process, out creation, out exit, out kernel, out user)) {
                    return 0;
                }
                return ((long)creation.dwHighDateTime << 32) +
                    ((long)creation.dwLowDateTime & 0xffffffffL);
            } finally {
                CloseHandle(process);
            }
        }

        public static string[] CommandLineToArgv(string commandLine) {
            int count;
            IntPtr argv = CommandLineToArgvW(commandLine, out count);
            if (argv == IntPtr.Zero) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            try {
                var result = new string[count];
                for (int index = 0; index < count; index++) {
                    IntPtr value = Marshal.ReadIntPtr(argv, index * IntPtr.Size);
                    result[index] = Marshal.PtrToStringUni(value);
                }
                return result;
            } finally {
                LocalFree(argv);
            }
        }
    }
}
'@
}

function Test-OperatorReparsePoint {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return $false
    }
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        return $true
    }
    return [NovelAudio.OperatorNative]::IsReparsePoint($Path)
}

function Assert-NoReparsePath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [switch]$AllowMissing
    )

    $full = [System.IO.Path]::GetFullPath($Path)
    $current = $full
    while ($null -ne $current -and $current.Length -gt 0) {
        if (Test-Path -LiteralPath $current) {
            if (Test-OperatorReparsePoint $current) {
                throw "reparse point is not allowed: $current"
            }
            try {
                [NovelAudio.OperatorNative]::AssertNoReparsePoint($current)
            } catch {
                throw "reparse point is not allowed: $current"
            }
        } elseif (-not $AllowMissing) {
            throw "path does not exist: $current"
        }
        $parent = [System.IO.Directory]::GetParent($current)
        if ($null -eq $parent -or $parent.FullName -eq $current) {
            break
        }
        $current = $parent.FullName
    }
    if (-not $AllowMissing -and -not (Test-Path -LiteralPath $full)) {
        throw "path does not exist: $full"
    }
}

function Get-OperatorContext {
    param(
        [Parameter(Mandatory = $true)][string]$ConfigPath,
        [switch]$AllowMissingConfig
    )

    $fullConfig = [System.IO.Path]::GetFullPath($ConfigPath)
    $root = [System.IO.Directory]::GetParent($fullConfig).FullName
    Assert-NoReparsePath -Path $root -AllowMissing
    Assert-NoReparsePath -Path $fullConfig -AllowMissing:$AllowMissingConfig
    if ((Test-Path -LiteralPath $fullConfig) -and
        -not (Test-Path -LiteralPath $fullConfig -PathType Leaf)) {
        throw "config is not a file"
    }
    $scriptPath = [System.IO.Path]::GetFullPath(
        (Join-Path $PSScriptRoot "server.py")
    )
    Assert-NoReparsePath -Path $scriptPath
    return [pscustomobject]@{
        ConfigPath = $fullConfig
        Root = $root
        State = Join-Path $root "state"
        ScriptPath = $scriptPath
        Guard = $null
    }
}

function Assert-OperatorState {
    param([Parameter(Mandatory = $true)]$Context)

    Assert-NoReparsePath -Path $Context.Root
    Assert-NoReparsePath -Path $Context.State -AllowMissing
    if ((Test-Path -LiteralPath $Context.State) -and
        -not (Test-Path -LiteralPath $Context.State -PathType Container)) {
        throw "state is not a directory"
    }
    foreach ($name in @(
        "agent.lock",
        "agent.pid",
        "agent.stop",
        "agent.status.json",
        "agent.status.json.tmp",
        "agent.owner.json"
    )) {
        $path = Join-Path $Context.State $name
        Assert-NoReparsePath -Path $path -AllowMissing
    }
}

function Open-OperatorState {
    param($Context, [switch]$Create)
    # Holding the ancestor and state handles prevents directory rename/replacement.
    $Context.Guard = [NovelAudio.OperatorNative]::PinDirectory($Context.State, [bool]$Create)
    Assert-OperatorState $Context
}

function Ensure-OperatorDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)

    Assert-NoReparsePath -Path $Path -AllowMissing
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
    Assert-NoReparsePath -Path $Path
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "not a directory: $Path"
    }
}

function Read-SafeText {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [System.Text.Encoding]$Encoding = [System.Text.Encoding]::UTF8
    )

    Assert-NoReparsePath -Path $Path
    $guard = [NovelAudio.OperatorNative]::PinDirectory((Split-Path -Parent $Path), $false)
    try {
        $stream = [NovelAudio.OperatorNative]::OpenSafeFile($Path, $false, $false)
        $reader = New-Object System.IO.StreamReader($stream, $Encoding)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $guard.Dispose() }
}

function Write-SafeStateText {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content,
        [System.Text.Encoding]$Encoding = [System.Text.Encoding]::ASCII
    )

    Assert-OperatorState $Context
    $path = Join-Path $Context.State $Name
    Assert-NoReparsePath -Path $path -AllowMissing
    $guard = [NovelAudio.OperatorNative]::PinDirectory($Context.State, $false)
    try {
        $stream = [NovelAudio.OperatorNative]::OpenSafeFile($path, $true, $false)
        try {
            Assert-OperatorState $Context
            $bytes = $Encoding.GetBytes($Content)
            $stream.SetLength(0)
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush()
        } finally { $stream.Dispose() }
    } finally { $guard.Dispose() }
    Assert-OperatorState $Context
}

function Touch-SafeStateFile {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)][string]$Name
    )

    Write-SafeStateText -Context $Context -Name $Name -Content ""
}

function Remove-SafeStateFile {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)][string]$Name
    )

    Assert-OperatorState $Context
    $path = Join-Path $Context.State $Name
    if (Test-Path -LiteralPath $path) {
        Assert-NoReparsePath -Path $path
        Remove-Item -LiteralPath $path -Force
    }
    Assert-OperatorState $Context
}

function Write-NewFileFromTemplate {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Assert-NoReparsePath -Path $Source
    $parent = [System.IO.Directory]::GetParent($Destination).FullName
    Ensure-OperatorDirectory $parent
    Assert-NoReparsePath -Path $Destination -AllowMissing
    if (Test-Path -LiteralPath $Destination) {
        return
    }
    $guard = [NovelAudio.OperatorNative]::PinDirectory($parent, $false)
    try {
        # CreateNew semantics: a concurrent installer can never overwrite a file.
        $bytes = [System.IO.File]::ReadAllBytes($Source)
        $stream = [NovelAudio.OperatorNative]::OpenSafeFile($Destination, $true, $true)
        try {
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush()
        } finally {
            $stream.Dispose()
        }
    } catch [System.IO.IOException] {
        if (-not (Test-Path -LiteralPath $Destination)) {
            throw
        }
    } finally { $guard.Dispose() }
    Assert-NoReparsePath -Path $Destination
}

function Initialize-OperatorTemplates {
    param([Parameter(Mandatory = $true)]$Context)

    Write-NewFileFromTemplate `
        -Source (Join-Path $PSScriptRoot "local-model.example.json") `
        -Destination $Context.ConfigPath
    Write-NewFileFromTemplate `
        -Source (Join-Path $PSScriptRoot "config/models.windows.example.json") `
        -Destination (Join-Path $Context.Root "config/models.windows.example.json")
    Write-NewFileFromTemplate `
        -Source (Join-Path $PSScriptRoot "voices/standard.json") `
        -Destination (Join-Path $Context.Root "voices/standard.json")
}

function Get-ConfiguredPython {
    param([Parameter(Mandatory = $true)]$Context)

    $config = ConvertFrom-Json (Read-SafeText -Path $Context.ConfigPath)
    $configured = [string]$config.tts.pythonExecutable
    if ([string]::IsNullOrWhiteSpace($configured)) {
        throw "tts.pythonExecutable is not configured"
    }
    if ([System.IO.Path]::IsPathRooted($configured)) {
        $python = [System.IO.Path]::GetFullPath($configured)
    } else {
        $python = [System.IO.Path]::GetFullPath((Join-Path $Context.Root $configured))
    }
    Assert-NoReparsePath -Path $python
    return $python
}

function Get-ConfiguredBasePython {
    param([Parameter(Mandatory = $true)]$Context)

    $python = Get-ConfiguredPython $Context
    $scripts = Split-Path -Parent $python
    if ((Split-Path -Leaf $scripts) -ine "Scripts") { return $python }
    $venvConfig = Join-Path (Split-Path -Parent $scripts) "pyvenv.cfg"
    if (-not (Test-Path -LiteralPath $venvConfig -PathType Leaf)) { return $python }
    $text = Read-SafeText $venvConfig
    $homeMatch = [regex]::Match($text, '(?m)^home\s*=\s*([^\r\n]+)')
    if (-not $homeMatch.Success) { throw "venv base interpreter is not configured" }
    $base = [IO.Path]::GetFullPath((Join-Path $homeMatch.Groups[1].Value.Trim() "python.exe"))
    Assert-NoReparsePath -Path $base
    return $base
}

function Resolve-AgentLaunchPin {
    param($Context, $LaunchPin, [datetime]$Deadline)

    $base = Get-ConfiguredBasePython $Context
    if (Test-ExactPathToken $base (Get-ConfiguredPython $Context)) { return $LaunchPin }
    while ((Get-Date) -lt $Deadline -and (Test-ProcessPin $LaunchPin)) {
        $agentMatches = @()
        foreach ($child in @(Get-ChildProcessRecords $LaunchPin)) {
            if ((Test-ExactPathToken $child.Process.ExecutablePath $base) -and
                (Test-AgentCommand $child.Process $Context)) {
                $agentMatches += $child.Pin
            } else { $child.Pin.Handle.Dispose() }
        }
        if ($agentMatches.Count -eq 1) { return $agentMatches[0] }
        foreach ($candidate in $agentMatches) { $candidate.Handle.Dispose() }
        if ($agentMatches.Count -gt 1) { throw "ambiguous agent descendants" }
        Start-Sleep -Milliseconds 100
    }
    throw "owned venv agent did not appear"
}

function Get-ConfigHash {
    param([Parameter(Mandatory = $true)]$Context)

    Assert-NoReparsePath -Path $Context.ConfigPath
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes((Read-SafeText $Context.ConfigPath))
        return ([System.BitConverter]::ToString(
            $sha.ComputeHash($bytes)
        ) -replace "-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-AgentRecord {
    param([Parameter(Mandatory = $true)]$Context)

    Assert-OperatorState $Context
    $pidPath = Join-Path $Context.State "agent.pid"
    if (-not (Test-Path -LiteralPath $pidPath -PathType Leaf)) {
        $ownerPath = Join-Path $Context.State "agent.owner.json"
        if (Test-Path -LiteralPath $ownerPath -PathType Leaf) {
            throw "incomplete agent ownership state"
        }
        return $null
    }
    $raw = (Read-SafeText -Path $pidPath -Encoding ([System.Text.Encoding]::ASCII)).Trim()
    $agentPid = 0
    if (-not [int]::TryParse($raw, [ref]$agentPid) -or $agentPid -le 0) {
        throw "invalid agent pid"
    }
    $process = Get-CimInstance Win32_Process `
        -Filter "ProcessId = $agentPid" -ErrorAction Stop | Select-Object -First 1
    $ownerPath = Join-Path $Context.State "agent.owner.json"
    $owner = $null
    if (Test-Path -LiteralPath $ownerPath -PathType Leaf) {
        $owner = ConvertFrom-Json (Read-SafeText -Path $ownerPath)
    }
    return [pscustomobject]@{
        Pid = $agentPid
        Process = $process
        Owner = $owner
    }
}

function New-ProcessPin {
    param([Parameter(Mandatory = $true)]$Process)

    # Retain a native handle throughout termination; Windows cannot reuse this PID
    # while the process object is still referenced. CIM alone leaves a kill race.
    $handle = [NovelAudio.OperatorNative]::PinProcess([int]$Process.ProcessId)
    $ticks = [NovelAudio.OperatorNative]::GetProcessCreationTicks(
        [int]$Process.ProcessId
    )
    if ($ticks -le 0 -or [string]::IsNullOrWhiteSpace([string]$Process.CreationDate)) {
        $handle.Dispose()
        throw "process creation time unavailable"
    }
    return [pscustomobject]@{
        ProcessId = [int]$Process.ProcessId
        CreationDate = [string]$Process.CreationDate
        CreationTicks = [int64]$ticks
        Handle = $handle
    }
}

function Test-ProcessPin {
    param(
        [Parameter(Mandatory = $true)]$Pin,
        [switch]$ThrowOnChange
    )

    $current = Get-CimInstance Win32_Process `
        -Filter "ProcessId = $($Pin.ProcessId)" `
        -ErrorAction Stop | Select-Object -First 1
    if ($null -eq $current) {
        return $false
    }
    $ticks = [NovelAudio.OperatorNative]::GetProcessCreationTicks(
        [int]$Pin.ProcessId
    )
    $same = (
        [string]$current.CreationDate -eq [string]$Pin.CreationDate -and
        [int64]$ticks -eq [int64]$Pin.CreationTicks
    )
    if (-not $same -and $ThrowOnChange) {
        throw "process identity changed"
    }
    return $same
}

function Test-ExactPathToken {
    param(
        [Parameter(Mandatory = $true)][string]$Actual,
        [Parameter(Mandatory = $true)][string]$Expected
    )

    try {
        if ($Actual -notmatch '^[A-Za-z]:[\\/]') { return $false }
        $actualPath = [System.IO.Path]::GetFullPath($Actual)
        $expectedPath = [System.IO.Path]::GetFullPath($Expected)
    } catch {
        return $false
    }
    return [string]::Equals(
        $actualPath.TrimEnd("\"),
        $expectedPath.TrimEnd("\"),
        [System.StringComparison]::OrdinalIgnoreCase
    )
}

function Test-AgentCommand {
    param(
        [Parameter(Mandatory = $true)]$Process,
        [Parameter(Mandatory = $true)]$Context
    )

    if ([string]::IsNullOrWhiteSpace([string]$Process.CommandLine)) {
        return $false
    }
    try {
        $arguments = [NovelAudio.OperatorNative]::CommandLineToArgv(
            [string]$Process.CommandLine
        )
    } catch {
        return $false
    }
    if ($arguments.Count -ne 5) {
        return $false
    }
    if (-not (Test-ExactPathToken $arguments[1] $Context.ScriptPath)) {
        return $false
    }
    $python = Get-ConfiguredPython $Context
    $base = Get-ConfiguredBasePython $Context
    $argumentOwned = (Test-ExactPathToken $arguments[0] $python) -or
        (Test-ExactPathToken $arguments[0] $base)
    $imageOwned = (Test-ExactPathToken ([string]$Process.ExecutablePath) $python) -or
        (Test-ExactPathToken ([string]$Process.ExecutablePath) $base)
    if (-not $argumentOwned -or -not $imageOwned) { return $false }
    if ($arguments[2] -ceq "--serve" -and $arguments[3] -ceq "--config") {
        return Test-ExactPathToken $arguments[4] $Context.ConfigPath
    }
    if ($arguments[2] -ceq "--config" -and $arguments[4] -ceq "--serve") {
        return Test-ExactPathToken $arguments[3] $Context.ConfigPath
    }
    return $false
}

function Read-AgentOwner {
    param([Parameter(Mandatory = $true)]$Context)

    Assert-OperatorState $Context
    $path = Join-Path $Context.State "agent.owner.json"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return $null
    }
    return ConvertFrom-Json (Read-SafeText -Path $path)
}

function Save-AgentOwner {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$Pin
    )

    $owner = @{
        pid = [int]$Pin.ProcessId
        creationDate = [string]$Pin.CreationDate
        creationTicks = [int64]$Pin.CreationTicks
        configHash = Get-ConfigHash $Context
    } | ConvertTo-Json -Compress
    Write-SafeStateText -Context $Context -Name "agent.owner.json" `
        -Content $owner -Encoding ([System.Text.Encoding]::ASCII)
}

function Assert-StateOwner {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)][int]$AgentPid,
        [Parameter(Mandatory = $true)]$Pin
    )

    Assert-OperatorState $Context
    $pidPath = Join-Path $Context.State "agent.pid"
    if (Test-Path -LiteralPath $pidPath -PathType Leaf) {
        $actual = (Read-SafeText -Path $pidPath `
            -Encoding ([System.Text.Encoding]::ASCII)).Trim()
        if ($actual -ne [string]$AgentPid) {
            throw "state owner changed"
        }
    }
    $owner = Read-AgentOwner $Context
    if ($null -ne $owner) {
        if ([int]$owner.pid -ne $AgentPid -or
            [string]$owner.creationDate -ne [string]$Pin.CreationDate -or
            [int64]$owner.creationTicks -ne [int64]$Pin.CreationTicks -or
            [string]$owner.configHash -ne (Get-ConfigHash $Context)) {
            throw "state owner changed"
        }
    }
}

function Enter-StateLock {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [int64]$Offset = 0
    )

    Assert-OperatorState $Context
    Ensure-OperatorDirectory $Context.State
    $path = Join-Path $Context.State "agent.lock"
    Assert-NoReparsePath -Path $path -AllowMissing
    $stream = [NovelAudio.OperatorNative]::OpenSafeFile($path, $true, $false)
    try {
        # Locking beyond EOF is legal on Windows; do not resize the live Agent lock.
        Assert-OperatorState $Context
        $stream.Lock($Offset, 1)
        return [pscustomobject]@{ Stream = $stream; Offset = $Offset }
    } catch {
        $stream.Dispose()
        throw "operator busy"
    }
}

function Enter-OperatorLock {
    param($Context, [int]$TimeoutSeconds = 5)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Assert-OperatorState $Context
        try { return Enter-StateLock $Context 1 } catch {
            if ((Get-Date) -ge $deadline) { throw "operator busy" }
            Start-Sleep -Milliseconds 100
        }
    } while ($true)
}

function Exit-OperatorLock {
    param($Lock)

    if ($null -eq $Lock -or $null -eq $Lock.Stream) {
        return
    }
    try {
        $Lock.Stream.Unlock($Lock.Offset, 1)
    } finally {
        $Lock.Stream.Dispose()
    }
}

function Get-HealthToken {
    param([Parameter(Mandatory = $true)]$Context)

    $config = ConvertFrom-Json (Read-SafeText -Path $Context.ConfigPath)
    $tokenPathText = [string]$config.tokenFile
    if ([string]::IsNullOrWhiteSpace($tokenPathText)) {
        throw "token file is not configured"
    }
    if ([System.IO.Path]::IsPathRooted($tokenPathText)) {
        $tokenPath = [System.IO.Path]::GetFullPath($tokenPathText)
    } else {
        $tokenPath = [System.IO.Path]::GetFullPath(
            (Join-Path $Context.Root $tokenPathText)
        )
    }
    if (-not $tokenPath.StartsWith(
        $Context.Root.TrimEnd("\") + "\", [System.StringComparison]::OrdinalIgnoreCase
    )) { throw "token outside config root" }
    Assert-NoReparsePath -Path $tokenPath
    $token = (Read-SafeText -Path $tokenPath `
        -Encoding ([System.Text.Encoding]::ASCII)).Trim()
    if ($token -cnotmatch '^[A-Za-z0-9_-]{32,4096}$') {
        throw "token is empty"
    }
    return $token
}

function Test-AgentHealth {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$Pin,
        [int]$TimeoutMilliseconds = 1000
    )

    try {
        $config = ConvertFrom-Json (Read-SafeText -Path $Context.ConfigPath)
        $port = [int]$config.port
        if ($port -lt 1024 -or $port -gt 65535) { return $false }
        $address = "127.0.0.1"
        if ([string]$config.host -in @("::", "::1")) { $address = "[::1]" }
        elseif ([string]$config.host -notin @("127.0.0.1", "0.0.0.0")) { return $false }
        # A healthy service on a stolen port is not proof that this PID owns it.
        if (-not (Test-AgentListener $Pin $port)) { return $false }
        $token = Get-HealthToken $Context
        $request = [System.Net.HttpWebRequest]::Create(
            "http://${address}:$port/v1/health"
        )
        $request.Method = "GET"
        $request.Timeout = $TimeoutMilliseconds
        $request.ReadWriteTimeout = $TimeoutMilliseconds
        $request.AllowAutoRedirect = $false
        $request.Proxy = $null
        $request.Headers["Authorization"] = "Bearer $token"
        $response = $request.GetResponse()
        try {
            if ([int]$response.StatusCode -ne 200) {
                return $false
            }
            $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
            try {
                $buffer = New-Object char[] 4097
                $count = $reader.ReadBlock($buffer, 0, $buffer.Length)
                if ($count -gt 4096) { return $false }
                $body = -join $buffer[0..($count - 1)]
            } finally {
                $reader.Dispose()
            }
        } finally {
            $response.Dispose()
        }
        $health = ConvertFrom-Json $body
        return (
            [string]$health.status -eq "ok" -and
            $health.directorReady -is [bool] -and $health.directorReady -and
            $health.ttsReady -is [bool] -and $health.ttsReady -and
            [string]$health.apiVersion -ceq "1" -and
            (Test-ProcessPin $Pin) -and (Test-AgentListener $Pin $port)
        )
    } catch {
        return $false
    }
}

function Test-AgentListener {
    param($Pin, [int]$Port)
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop)
    return @($listeners | Where-Object {
        $_.OwningProcess -eq $Pin.ProcessId -and
        $_.LocalAddress -in @("127.0.0.1", "0.0.0.0", "::1", "::")
    }).Count -gt 0
}

function Invoke-OperatorCheck {
    param([Parameter(Mandatory = $true)]$Context)

    $python = Get-ConfiguredPython $Context
    & $python $Context.ScriptPath --check --require-windows-runtime `
        --config $Context.ConfigPath | Out-Host
    return [int]$LASTEXITCODE
}

function Get-ChildProcessRecords {
    param([Parameter(Mandatory = $true)]$ParentPin)

    $children = Get-CimInstance Win32_Process `
        -Filter "ParentProcessId = $($ParentPin.ProcessId)" `
        -ErrorAction Stop
    foreach ($child in @($children)) {
        if ($null -eq $child.CreationDate) {
            continue
        }
        $childPin = New-ProcessPin $child
        if ([int64]$childPin.CreationTicks -lt [int64]$ParentPin.CreationTicks) {
            $childPin.Handle.Dispose()
            continue
        }
        [pscustomobject]@{
            Pin = $childPin
            Process = $child
        }
    }
}

function Add-OwnedChildren {
    param(
        [Parameter(Mandatory = $true)]$ParentPin,
        [Parameter(Mandatory = $true)]$Owned
    )

    if (-not (Test-ProcessPin $ParentPin)) {
        return
    }
    foreach ($child in @(Get-ChildProcessRecords $ParentPin)) {
        $key = [string]$child.Pin.ProcessId
        if (-not $Owned.ContainsKey($key)) {
            $Owned[$key] = $child.Pin
            Add-OwnedChildren -ParentPin $child.Pin -Owned $Owned
        } else { $child.Pin.Handle.Dispose() }
    }
}

function Get-AliveOwnedRecords {
    param([Parameter(Mandatory = $true)]$Owned)

    $alive = @()
    foreach ($pin in @($Owned.Values)) {
        $current = Get-CimInstance Win32_Process `
            -Filter "ProcessId = $($pin.ProcessId)" `
            -ErrorAction Stop | Select-Object -First 1
        if ($null -eq $current) {
            continue
        }
        if (-not (Test-ProcessPin $pin)) {
            throw "process identity changed"
        }
        $alive += $pin
    }
    return $alive
}

function Invoke-AgentTermination {
    param([Parameter(Mandatory = $true)][int]$AgentPid)

    # The caller must have proved this PID and CreationDate immediately before entry.
    & "$env:SystemRoot\System32\taskkill.exe" /PID ([string]$AgentPid) /F 2>$null | Out-Null
}

function Stop-OwnedRecords {
    param(
        [Parameter(Mandatory = $true)]$Owned,
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($pin in @($Owned.Values)) {
            Add-OwnedChildren -ParentPin $pin -Owned $Owned
        }
        $alive = @(Get-AliveOwnedRecords $Owned)
        if ($alive.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 200
    }

    # Kill only pinned processes, not a newly enumerated tree at termination time.
    foreach ($pin in @(Get-AliveOwnedRecords $Owned)) {
        Assert-OperatorState $Context
        if (Test-ProcessPin $pin) {
            Invoke-AgentTermination -AgentPid $pin.ProcessId
        }
    }
    $deadline = (Get-Date).AddSeconds(5)
    while ((Get-Date) -lt $deadline) {
        $alive = @(Get-AliveOwnedRecords $Owned)
        if ($alive.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 100
    }
    if (@(Get-AliveOwnedRecords $Owned).Count -ne 0) {
        throw "stop failed"
    }
}

function Wait-ForHealthyAgent {
    param(
        [Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)]$Pin,
        [Parameter(Mandatory = $true)][datetime]$Deadline
    )

    while ((Get-Date) -lt $Deadline) {
        if (-not (Test-ProcessPin $Pin)) {
            return $false
        }
        if (Test-AgentHealth $Context $Pin) {
            return $true
        }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

function Clear-StaleAgentState {
    param([Parameter(Mandatory = $true)]$Context)

    Assert-OperatorState $Context
    foreach ($name in @("agent.pid", "agent.owner.json", "agent.stop")) {
        Remove-SafeStateFile -Context $Context -Name $name
    }
}
