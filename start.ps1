param([switch]$Restart)

$ErrorActionPreference = 'Stop'
$backendPort = if ($env:PORT) { [int]$env:PORT } else { 8080 }
$backendUrl = "http://localhost:$backendPort"

if ($Restart) {
    $listeners = @(Get-NetTCPConnection -LocalPort $backendPort -State Listen -ErrorAction SilentlyContinue)
    foreach ($serverProcessId in ($listeners.OwningProcess | Select-Object -Unique)) {
        $serverProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $serverProcessId"
        $serverParent = Get-CimInstance Win32_Process -Filter "ProcessId = $($serverProcess.ParentProcessId)"
        if ($serverProcess.CommandLine -notlike '*com.edura.schoolos.SchoolOsApplication*' -or
            ($serverProcess.CommandLine -notlike "*$PSScriptRoot*" -and $serverParent.CommandLine -notlike "*$PSScriptRoot*")) {
            throw "Port $backendPort belongs to a process that could not be verified as this project's backend. It was not stopped."
        }
        Stop-Process -Id $serverProcessId
        Wait-Process -Id $serverProcessId -Timeout 15 -ErrorAction SilentlyContinue
        Write-Host 'Stopped the previous backend. Starting the current source code.'
    }
}

# Reuse a healthy server instead of launching a second instance on the same port.
$backendHealth = $null
try {
    $backendHealth = Invoke-RestMethod "$backendUrl/actuator/health" -TimeoutSec 5
} catch {
    # A stopped server is expected on the first start.
}
if ($backendHealth.status -eq 'UP') {
    Write-Host "Backend is already running successfully at $backendUrl" -ForegroundColor Green
    Write-Host 'Refresh the portal in your browser. No second Maven process is needed.'
    return
}

$portProbe = New-Object System.Net.Sockets.TcpClient
try {
    $portProbe.Connect('localhost', $backendPort)
    throw "Port $backendPort is occupied but the backend is not healthy. Check the existing server logs before restarting."
} catch [System.Net.Sockets.SocketException] {
    # The port is available; start the backend below.
} finally {
    $portProbe.Dispose()
}

$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mavenCommand) { throw 'Maven was not found. Add Maven to PATH and run this script again.' }
Push-Location $PSScriptRoot
try {
    & $mavenCommand.Source spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Backend stopped with exit code $LASTEXITCODE. Read the first error above the Maven summary for the cause."
    }
} finally {
    Pop-Location
}
