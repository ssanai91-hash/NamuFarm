# Windows x64 build setup and EXE packaging (Python 3.12).
# From the repository root:
# powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\desktop\setup_windows_build.ps1
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
if (-not [Environment]::Is64BitOperatingSystem) {
    throw "Windows x64 is required."
}
Set-Location $PSScriptRoot
$pythonPath = Join-Path $env:LOCALAPPDATA "Programs\Python\Python312\python.exe"
$launcher = Get-Command py -ErrorAction SilentlyContinue
if ($launcher) {
    $detected = & $launcher.Source -3.12 -c "import sys; print(sys.executable)" 2>$null
    if ($LASTEXITCODE -eq 0 -and $detected) {
        $pythonPath = "$detected".Trim()
    }
}
if (-not (Test-Path $pythonPath)) {
    $installerPath = Join-Path $env:TEMP "namufarm-python-3.12.10-amd64.exe"
    try {
        Write-Host "Downloading Python 3.12.10 x64..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri "https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe" -OutFile $installerPath
        $signature = Get-AuthenticodeSignature $installerPath
        if ($signature.Status -ne "Valid" -or $signature.SignerCertificate.Subject -notmatch "Python Software Foundation") {
            throw "Python installer signature validation failed."
        }
        $install = Start-Process -FilePath $installerPath -ArgumentList @(
            "/quiet", "InstallAllUsers=0", "Include_pip=1", "Include_launcher=0",
            "Include_test=0", "PrependPath=0"
        ) -Wait -PassThru
        if ($install.ExitCode -notin @(0, 3010)) {
            throw "Python installation failed: $($install.ExitCode)"
        }
    } finally {
        Remove-Item $installerPath -Force -ErrorAction SilentlyContinue
    }
}
if (-not (Test-Path $pythonPath)) {
    throw "Python 3.12 was not found after setup."
}
& $pythonPath -c "import sys, struct; assert sys.version_info[:2] == (3, 12) and struct.calcsize('P') == 8, 'Python 3.12 x64 is required'"
if ($LASTEXITCODE -ne 0) { throw "Python version or architecture is incorrect." }
$buildPython = Join-Path $PSScriptRoot ".venv-build\Scripts\python.exe"
if (-not (Test-Path $buildPython)) {
    & $pythonPath -m venv (Join-Path $PSScriptRoot ".venv-build")
    if ($LASTEXITCODE -ne 0) { throw "Could not create the build environment." }
}
& $buildPython -m pip install -e . "pyinstaller>=6.11,<7"
if ($LASTEXITCODE -ne 0) { throw "Build dependency installation failed." }
& $buildPython -m PyInstaller --noconfirm --clean --onefile --windowed --name NamuFarm-PC --manifest windows.manifest --collect-data namufarm_desktop run_desktop.py
if ($LASTEXITCODE -ne 0) { throw "EXE packaging failed." }
$exePath = Join-Path $PSScriptRoot "dist\NamuFarm-PC.exe"
if (-not (Test-Path $exePath)) { throw "EXE output is missing." }
Write-Host "Built: $exePath"
