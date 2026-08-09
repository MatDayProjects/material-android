[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $RuntimeDirectory,

    [string] $AdbPath = "",
    [string] $DeviceSerial = "",

    [ValidateSet("x86_64", "aarch64")]
    [string] $GuestArchitecture = "x86_64",

    [string] $KernelPath = "",
    [string] $InitrdPath = "",
    [string] $RawDiskPath = "",
    [string] $EvidenceDirectory = "",

    [ValidateRange(1, 30)]
    [int] $EventLoopSeconds = 2,

    [ValidateRange(5, 300)]
    [int] $BootTimeoutSeconds = 45
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:AdbPrefix = @()

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,
        [switch] $AllowFailure
    )

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& $AdbPath @script:AdbPrefix @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $output = ($lines | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "adb failed with exit code $exitCode for '$($Arguments -join ' ')':$([Environment]::NewLine)$output"
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}

function Invoke-DeviceShell {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Command,
        [switch] $AllowFailure
    )

    return Invoke-Adb -Arguments @("shell", $Command) -AllowFailure:$AllowFailure
}

function Save-TextEvidence {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name,
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $Value
    )

    Set-Content -LiteralPath (Join-Path $EvidenceDirectory $Name) -Value $Value -Encoding utf8
}

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $sdkRoots = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk" })
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($sdkRoot in $sdkRoots) {
        $candidate = Join-Path $sdkRoot "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $AdbPath = $candidate
            break
        }
    }
}
if ([string]::IsNullOrWhiteSpace($AdbPath) -or -not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "adb.exe was not found. Pass -AdbPath or configure ANDROID_SDK_ROOT/ANDROID_HOME."
}
$AdbPath = (Resolve-Path -LiteralPath $AdbPath).Path

$runtimeRoot = (Resolve-Path -LiteralPath $RuntimeDirectory).Path
$runtimeManifestPath = Join-Path $runtimeRoot "runtime.json"
if (-not (Test-Path -LiteralPath $runtimeManifestPath -PathType Leaf)) {
    throw "The runtime manifest is missing: $runtimeManifestPath"
}
$runtimeManifest = Get-Content -LiteralPath $runtimeManifestPath -Raw -Encoding utf8 | ConvertFrom-Json
if ($runtimeManifest.schemaVersion -ne 1) {
    throw "Unsupported runtime manifest schema: $($runtimeManifest.schemaVersion)"
}

$qemuDataFiles = @($runtimeManifest.qemuDataFiles)
if ($qemuDataFiles -notcontains "linuxboot_dma.bin") {
    throw "The runtime is missing the linuxboot_dma.bin direct-kernel firmware contract."
}
$qemuDataDirectory = Join-Path $runtimeRoot "share\qemu"
$actualDataFiles = @(
    Get-ChildItem -LiteralPath $qemuDataDirectory -File | ForEach-Object Name | Sort-Object
)
$expectedDataFiles = @($qemuDataFiles | Sort-Object)
$dataDifference = @(Compare-Object -ReferenceObject $expectedDataFiles -DifferenceObject $actualDataFiles)
if ($dataDifference.Count -ne 0) {
    throw "The runtime firmware directory does not exactly match runtime.json."
}

$binaryRecord = $runtimeManifest.binaries.$GuestArchitecture
if ($null -eq $binaryRecord) {
    throw "runtime.json does not contain a $GuestArchitecture guest executable."
}
$binaryPath = Join-Path $runtimeRoot $binaryRecord.path
if (-not (Test-Path -LiteralPath $binaryPath -PathType Leaf)) {
    throw "The QEMU guest executable is missing: $binaryPath"
}
$binaryLength = (Get-Item -LiteralPath $binaryPath).Length
$binaryHash = (Get-FileHash -LiteralPath $binaryPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($binaryLength -ne [long]$binaryRecord.sizeBytes -or $binaryHash -ne $binaryRecord.sha256) {
    throw "The QEMU guest executable does not match runtime.json."
}

if ([string]::IsNullOrWhiteSpace($KernelPath) -xor [string]::IsNullOrWhiteSpace($InitrdPath)) {
    throw "Pass both -KernelPath and -InitrdPath, or neither."
}
$exerciseBoot = -not [string]::IsNullOrWhiteSpace($KernelPath)
if ($exerciseBoot) {
    $KernelPath = (Resolve-Path -LiteralPath $KernelPath).Path
    $InitrdPath = (Resolve-Path -LiteralPath $InitrdPath).Path
    if (-not [string]::IsNullOrWhiteSpace($RawDiskPath)) {
        $RawDiskPath = (Resolve-Path -LiteralPath $RawDiskPath).Path
    }
}

if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $evidenceName = "openvm-device-smoke-{0}" -f [DateTimeOffset]::UtcNow.ToString("yyyyMMddTHHmmssZ")
    $EvidenceDirectory = Join-Path ([System.IO.Path]::GetTempPath()) $evidenceName
}
$null = New-Item -ItemType Directory -Path $EvidenceDirectory -Force
$EvidenceDirectory = (Resolve-Path -LiteralPath $EvidenceDirectory).Path

$deviceList = Invoke-Adb -Arguments @("devices")
$connectedDevices = @(
    $deviceList.Output -split "`r?`n" |
        Where-Object { $_ -match "^([^\s]+)\s+device$" } |
        ForEach-Object { $Matches[1] }
)
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($connectedDevices.Count -ne 1) {
        throw "Expected exactly one ready Android device; found $($connectedDevices.Count). Pass -DeviceSerial to choose one."
    }
    $DeviceSerial = $connectedDevices[0]
} elseif ($connectedDevices -notcontains $DeviceSerial) {
    throw "Android device '$DeviceSerial' is not ready."
}
$script:AdbPrefix = @("-s", $DeviceSerial)
$null = Invoke-Adb -Arguments @("get-state")

$startedAt = [DateTimeOffset]::UtcNow
$runId = "{0}-{1}" -f $PID, $startedAt.ToUnixTimeMilliseconds()
$deviceRoot = "/data/local/tmp/openvm-smoke-$runId"
if ($deviceRoot -notmatch '^/data/local/tmp/openvm-smoke-[0-9-]+$') {
    throw "Refusing an unsafe device scratch path: $deviceRoot"
}
$deviceRuntime = "$deviceRoot/runtime"
$deviceExecutable = "$deviceRuntime/$($binaryRecord.path)"
$deviceLibraryDirectory = "$deviceRuntime/lib"
$deviceDataDirectory = "$deviceRuntime/share/qemu"
$summary = [ordered]@{
    schemaVersion = 1
    startedAtUtc = $startedAt.ToString("o")
    completedAtUtc = $null
    success = $false
    deviceSerial = $DeviceSerial
    runtimeDirectory = $runtimeRoot
    runtimeAndroidAbi = $runtimeManifest.androidAbi
    guestArchitecture = $GuestArchitecture
    qemuSha256 = $binaryHash
    qemuVersion = $null
    eventLoopAlive = $false
    nestedBoot = $null
    error = $null
}
$failure = $null

try {
    $null = Invoke-Adb -Arguments @("shell", "mkdir", "-p", $deviceRuntime)
    $null = Invoke-Adb -Arguments @("push", (Join-Path $runtimeRoot "."), "$deviceRuntime/")
    $null = Invoke-DeviceShell -Command "chmod 700 $deviceExecutable"

    $versionResult = Invoke-DeviceShell -Command "LD_LIBRARY_PATH=$deviceLibraryDirectory $deviceExecutable --version"
    Save-TextEvidence -Name "qemu-version.txt" -Value $versionResult.Output
    if ($versionResult.Output -notmatch "QEMU emulator version\s+([0-9]+\.[0-9]+\.[0-9]+)") {
        throw "The on-device executable did not report a QEMU version."
    }
    $summary["qemuVersion"] = $Matches[1]

    $eventLog = "$deviceRoot/event-loop.log"
    $eventPid = "$deviceRoot/event-loop.pid"
    $eventCommand = "LD_LIBRARY_PATH=$deviceLibraryDirectory $deviceExecutable -machine none -nodefaults -no-user-config -display none -accel tcg -S >$eventLog 2>&1 & echo `$! >$eventPid"
    $null = Invoke-DeviceShell -Command $eventCommand
    Start-Sleep -Seconds $EventLoopSeconds
    $eventCheck = Invoke-DeviceShell -Command "pid=`$(cat $eventPid); kill -0 `$pid 2>/dev/null" -AllowFailure
    $eventStop = Invoke-DeviceShell -Command "pid=`$(cat $eventPid); kill `$pid 2>/dev/null || true; wait `$pid 2>/dev/null || true; cat $eventLog" -AllowFailure
    Save-TextEvidence -Name "qemu-event-loop.txt" -Value $eventStop.Output
    if ($eventCheck.ExitCode -ne 0) {
        throw "The on-device QEMU process exited before the $EventLoopSeconds-second event-loop checkpoint."
    }
    $summary["eventLoopAlive"] = $true

    if ($exerciseBoot) {
        $deviceKernel = "$deviceRoot/kernel"
        $deviceInitrd = "$deviceRoot/initrd.img"
        $deviceDisk = "$deviceRoot/guest.raw"
        $serialLog = "$deviceRoot/nested-serial.log"
        $qemuLog = "$deviceRoot/nested-qemu.log"
        $bootPid = "$deviceRoot/nested.pid"
        $null = Invoke-Adb -Arguments @("push", $KernelPath, $deviceKernel)
        $null = Invoke-Adb -Arguments @("push", $InitrdPath, $deviceInitrd)
        if ([string]::IsNullOrWhiteSpace($RawDiskPath)) {
            $null = Invoke-DeviceShell -Command "dd if=/dev/zero of=$deviceDisk bs=1048576 count=64"
        } else {
            $null = Invoke-Adb -Arguments @("push", $RawDiskPath, $deviceDisk)
        }

        if ($GuestArchitecture -eq "x86_64") {
            $machine = "q35,accel=tcg"
            $kernelCommandLine = "console=ttyS0 earlycon=uart8250,io,0x3f8 androidboot.hardware=ranchu androidboot.selinux=permissive printk.devkmsg=on"
        } else {
            $machine = "virt,accel=tcg"
            $kernelCommandLine = "console=ttyAMA0 earlycon androidboot.hardware=openvm androidboot.selinux=permissive printk.devkmsg=on"
        }
        $bootCommand = "LD_LIBRARY_PATH=$deviceLibraryDirectory $deviceExecutable -machine $machine -m 1024M -smp 2 -drive file=$deviceDisk,format=raw,if=virtio -nic none -display none -serial file:$serialLog -monitor none -no-reboot -L $deviceDataDirectory -kernel $deviceKernel -initrd $deviceInitrd -append '$kernelCommandLine' >$qemuLog 2>&1 & echo `$! >$bootPid"
        $null = Invoke-DeviceShell -Command $bootCommand

        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $bootAlive = $true
        while ($stopwatch.Elapsed.TotalSeconds -lt $BootTimeoutSeconds) {
            $bootCheck = Invoke-DeviceShell -Command "pid=`$(cat $bootPid); kill -0 `$pid 2>/dev/null" -AllowFailure
            if ($bootCheck.ExitCode -ne 0) {
                $bootAlive = $false
                break
            }
            Start-Sleep -Seconds 1
        }
        if ($bootAlive) {
            $null = Invoke-DeviceShell -Command "pid=`$(cat $bootPid); kill `$pid 2>/dev/null || true; wait `$pid 2>/dev/null || true" -AllowFailure
        }

        $serialDestination = Join-Path $EvidenceDirectory "nested-android-serial.log"
        $qemuDestination = Join-Path $EvidenceDirectory "nested-qemu.log"
        $null = Invoke-Adb -Arguments @("pull", $serialLog, $serialDestination)
        $null = Invoke-Adb -Arguments @("pull", $qemuLog, $qemuDestination)
        $serialText = Get-Content -LiteralPath $serialDestination -Raw -Encoding utf8
        $kernelReached = $serialText.Contains("Linux version ")
        $initReached = $serialText.Contains("Run /init as init process")
        $firstStageReached = $serialText.Contains("init first stage started!")
        $missingDiagnosticPartitions = $serialText.Contains("metadata, super, vbmeta")
        $summary["nestedBoot"] = [ordered]@{
            timedOut = $bootAlive
            serialBytes = (Get-Item -LiteralPath $serialDestination).Length
            kernelReached = $kernelReached
            initReached = $initReached
            firstStageReached = $firstStageReached
            missingDiagnosticPartitions = $missingDiagnosticPartitions
            rawDiskProvided = -not [string]::IsNullOrWhiteSpace($RawDiskPath)
        }
        if (-not ($kernelReached -and $initReached -and $firstStageReached)) {
            throw "The nested guest did not reach the Android first-stage init checkpoint."
        }
    }

    $summary["success"] = $true
} catch {
    $failure = $_
    $summary["error"] = $_.Exception.Message
} finally {
    $cleanup = Invoke-Adb -Arguments @("shell", "rm", "-rf", "--", $deviceRoot) -AllowFailure
    if ($cleanup.ExitCode -ne 0 -and $null -eq $failure) {
        $failure = [System.Management.Automation.RuntimeException]::new("Failed to remove device scratch path ${deviceRoot}: $($cleanup.Output)")
        $summary["success"] = $false
        $summary["error"] = $failure.Message
    }
    $summary["completedAtUtc"] = [DateTimeOffset]::UtcNow.ToString("o")
    $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "smoke-summary.json") -Encoding utf8
}

if ($null -ne $failure) {
    throw $failure
}

Write-Host "OpenVM native QEMU device smoke passed. Evidence: $EvidenceDirectory"
