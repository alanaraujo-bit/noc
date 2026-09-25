# Recompila e reinicia o Noc Companion (debug), desacoplado do shell.
Get-Process Noc -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500
Push-Location 'D:\PROJETOS\Noc\companion'
dotnet build src/Noc.Companion -v q -nologo 2>&1 | Select-String -Pattern ' error |êxito' | ForEach-Object { $_.Line.Trim() }
Pop-Location
Start-Process 'D:\PROJETOS\Noc\companion\src\Noc.Companion\bin\Debug\net10.0-windows\Noc.exe'
Start-Sleep -Seconds 6
'iniciado'
