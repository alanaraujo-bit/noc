# Reinicia o Companion headless de desenvolvimento, totalmente desacoplado do shell.
$root = Split-Path -Parent $PSScriptRoot
$data = Join-Path $root '.devdata'
New-Item -ItemType Directory -Force $data | Out-Null
Get-CimInstance Win32_Process -Filter "Name='dotnet.exe'" |
    Where-Object { $_.CommandLine -like '*Noc.DevHost*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Push-Location (Join-Path $root 'companion')
dotnet build tools/Noc.DevHost -v q -nologo | Select-String 'error' | ForEach-Object { $_.Line }
Pop-Location
$dll = Join-Path $root 'companion\tools\Noc.DevHost\bin\Debug\net10.0-windows\Noc.DevHost.dll'
Remove-Item (Join-Path $data 'devhost-cmd.txt') -ErrorAction SilentlyContinue
Start-Process dotnet -ArgumentList "`"$dll`"", '--data', "`"$data`"", '--approve' -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $data 'devhost.log') -RedirectStandardError (Join-Path $data 'devhost.err')
Start-Sleep -Seconds 6
Get-Content (Join-Path $data 'devhost.log') -Tail 4
